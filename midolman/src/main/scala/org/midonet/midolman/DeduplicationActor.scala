// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, MultiMap, PriorityQueue}
import scala.collection.{immutable, mutable}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor._
import akka.event.LoggingReceive
import com.yammer.metrics.core.Clock

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.Coordinator
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.packets.Ethernet
import org.midonet.util.BatchCollector
import org.midonet.sdn.flows.WildcardMatch

object DeduplicationActor {
    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class ApplyFlow(actions: Seq[FlowAction], cookie: Option[Int])

    case class ApplyFlowFor(msg: ApplyFlow, deduplicator: ActorRef)

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

    case object _ExpireCookies
}

class CookieGenerator(val start: Int, val increment: Int) {
    private var nextCookie = start

    def next: Int = {
        val ret = nextCookie
        nextCookie += increment
        ret
    }
}

class DeduplicationActor(
            val cookieGen: CookieGenerator,
            val dpConnPool: DatapathConnectionPool,
            val clusterDataClient: DataClient,
            val connectionCache: Cache,
            val traceMessageCache: Cache,
            val traceIndexCache: Cache,
            val metrics: PacketPipelineMetrics)
            extends Actor with ActorLogWithoutPath with SuspendedPacketQueue {

    import DatapathController.DatapathReady
    import DeduplicationActor._
    import PacketWorkflow._
    import VirtualTopologyActor.ConditionListRequest

    def datapathConn(packet: Packet) = dpConnPool.get(packet.getMatch.hashCode)

    var traceConditions = immutable.Seq[Condition]()

    var datapath: Datapath = null
    var dpState: DatapathState = null

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    // data structures to handle the duplicate packets.
    protected val cookieToDpMatch = HashMap[Int, FlowMatch]()
    protected val dpMatchToCookie = HashMap[FlowMatch, Int]()
    protected val cookieToPendedPackets: MultiMap[Int, Packet] =
        new HashMap[Int, mutable.Set[Packet]] with MultiMap[Int, Packet]

    protected val cookieTimeToLiveMillis  = 10000L
    protected val cookieExpirationCheckInterval = 5000 millis
    protected val packetSimulatorExpiry = 5000L //config.getArpTimeoutSeconds * 1000

    private val cookieExpirations: PriorityQueue[(FlowMatch, Long)] =
        new PriorityQueue[(FlowMatch, Long)]()(
            Ordering.by[(FlowMatch,Long), Long](_._2).reverse)

    override def preStart() {
        super.preStart()
        // Defer this until actor start-up finishes, so that the VTA
        // will have an actor (ie, self) in 'sender' to send replies to.
    }

    override def receive = super.receive orElse LoggingReceive {

        case DatapathReady(dp, state) =>
            if (null == datapath) {
                datapath = dp
                dpState = state
                val expInterval = cookieExpirationCheckInterval
                system.scheduler.schedule(expInterval, expInterval,
                                                  self, _ExpireCookies)
             }

        case HandlePackets(packets) =>
            /* Use an array and a while loop (as opposed to a list and a for loop
             * to minimize garbage generation (for loops are closures, while loops
             * are not).
             */
            val handlers: Array[Option[PacketHandler]] =
                Array.fill(packets.length)(None)
            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlers(i) = handlePacket(packets(i))
                i += 1
            }

            dispatcher.execute(new Runnable(){
                override def run() {
                    var i = 0
                    while (i < handlers.length) {
                        if (handlers(i) != None)
                            startWorkflow(handlers(i).get)
                        i += 1
                    }
                }
            })

        case ApplyFlow(actions, cookieOpt) => cookieOpt foreach { cookie =>
            cookieToPendedPackets.remove(cookie) foreach { pendedPackets =>
                cookieToDpMatch.remove(cookie) foreach {
                    dpMatch => dpMatchToCookie.remove(dpMatch)
                }

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (actions.nonEmpty) {
                    for (unpendedPacket <- pendedPackets) {
                        executePacket(cookie, unpendedPacket, actions)
                        metrics.pendedPackets.dec()
                    }
                } else {
                    metrics.packetsProcessed.mark(pendedPackets.size)
                }
            }

            if (actions.isEmpty) {
                system.eventStream.publish(DiscardPacket(cookie))
            }
        }

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            dispatcher.execute(new Runnable(){
                override def run() {
                    startWorkflow(
                        workflow(Packet.fromEthernet(ethernet), Right(egressPort)))
                }
            })

        case TraceConditions(newTraceConditions) =>
            log.debug("traceConditions updated to {}", newTraceConditions)
            traceConditions = newTraceConditions

        case DeduplicationActor._ExpireCookies => {
            val now = Platform.currentTime
            while (cookieExpirations.nonEmpty && cookieExpirations.head._2 < now) {
                val (flowMatch, _) = cookieExpirations.dequeue()
                dpMatchToCookie.remove(flowMatch) match {
                    case Some(cookie) =>
                        log.warning("Expiring cookie:{}", cookie)
                        cookieToPendedPackets.remove(cookie)
                        cookieToDpMatch.remove(cookie)
                    case _ => // do nothing
                }
            }
        }

    }

    protected def workflow(packet: Packet,
                           cookieOrEgressPort: Either[Int, UUID],
                           parentCookie: Option[Int] = None): PacketHandler = {
        log.debug("Creating new PacketWorkflow for {}", cookieOrEgressPort)
        val (cookie, egressPort) = cookieOrEgressPort match {
            case Left(c) => (Some(c), None)
            case Right(id) =>
                packet.setMatch(FlowMatches.fromEthernetPacket(packet.getPacket))
                (None, Some(id))
        }

        val wcMatch = WildcardMatch.fromFlowMatch(packet.getMatch)

        PacketWorkflow(self, datapathConn(packet), dpState, datapath,
                clusterDataClient, packet, wcMatch, cookieOrEgressPort,
                parentCookie)
        {
            val expiry = Platform.currentTime + packetSimulatorExpiry
            new Coordinator(wcMatch, packet.getPacket, cookie, egressPort,
                expiry, connectionCache, traceMessageCache, traceIndexCache,
                parentCookie, traceConditions).simulate()
        }
    }

    protected def startWorkflow(pw: PacketHandler) {
        pw.start() andThen {
            case Success(path) =>
                log.debug("Packet with {} processed.", pw.cookieStr)
                pw.cookie match {
                    case Some(c) =>
                        val latency = (Clock.defaultClock().tick() -
                            pw.packet.getStartTimeNanos).toInt
                        metrics.packetsProcessed.mark()
                        path match {
                            case WildcardTableHit =>
                                metrics.wildcardTableHit(latency)
                            case PacketToPortSet =>
                                metrics.packetToPortSet(latency)
                            case Simulation =>
                                metrics.packetSimulated(latency)
                            case _ =>
                        }
                    case _ => // do nothing
                }
            case Failure(ex) =>
                log.warning("Exception while processing packet {} - {}, {}",
                    pw.cookieStr, ex.getMessage, ex.getStackTraceString)
                pw.cookie foreach { _ => metrics.packetsProcessed.mark() }
        }
    }

    private def handlePacket(packet: Packet): Option[PacketHandler] = {
        val wcMatch = packet.getMatch
        log.debug("Handling packet with match {}", wcMatch)
        dpMatchToCookie.get(wcMatch) match {
            case None =>
                // If there is no entry for the wildcard match, create a new
                // cookie and a new PacketWorkflow to handle the packet
                val newCookie = cookieGen.next
                log.debug("new cookie #{} for new match {}", newCookie, wcMatch)
                dpMatchToCookie.put(wcMatch, newCookie)
                cookieToDpMatch.put(newCookie, wcMatch)
                scheduleCookieExpiration(wcMatch)
                cookieToPendedPackets.put(newCookie, mutable.Set.empty)
                Some(workflow(packet, Left(newCookie)))

            case Some(cookie) =>
                // Simulation in progress. Just pend the packet.
                log.debug("A matching packet with cookie {} is already " +
                    "being handled", cookie)
                cookieToPendedPackets.addBinding(cookie, packet)
                metrics.pendedPackets.inc()
                None
        }
    }

    private def scheduleCookieExpiration(flowMatch: FlowMatch) {
        cookieExpirations +=
            ((flowMatch, Platform.currentTime + cookieTimeToLiveMillis))
    }

    private def executePacket(cookie: Int,
                              packet: Packet,
                              actions: Seq[FlowAction]) {
        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly)
            UserspaceFlowActionTranslator.translate(packet)

        if (!packet.getActions.isEmpty) {
            log.debug("Sending pended packet {} for cookie {}", packet, cookie)
            try {
                datapathConn(packet).packetsExecute(datapath, packet)
            } catch {
                case e: NetlinkException => log.info("Failed to execute packet: {}", e)
            }
            metrics.packetsProcessed.mark()
        }
    }

}
