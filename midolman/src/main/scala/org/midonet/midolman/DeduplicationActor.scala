// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.Future
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
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.packets.Ethernet
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

    case class RestartWorkflow(pw: PacketHandler)
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
            val metrics: PacketPipelineMetrics,
            val packetOut: () => Unit)
            extends Actor with ActorLogWithoutPath {

    import DatapathController.DatapathReady
    import DeduplicationActor._
    import PacketWorkflow._

    def datapathConn(packet: Packet) = dpConnPool.get(packet.getMatch.hashCode)

    var traceConditions = immutable.Seq[Condition]()

    var datapath: Datapath = null
    var dpState: DatapathState = null

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    // data structures to handle the duplicate packets.
    protected val cookieToDpMatch = mutable.HashMap[Int, FlowMatch]()
    protected val dpMatchToCookie = mutable.HashMap[FlowMatch, Int]()
    protected val cookieToPendedPackets: mutable.MultiMap[Int, Packet] =
                                new mutable.HashMap[Int, mutable.Set[Packet]]
                                with mutable.MultiMap[Int, Packet]

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketHandler](
        giveUpWorkflows, (simulationExpireMillis millis).toNanos)

    override def preStart() {
        super.preStart()
        // Defer this until actor start-up finishes, so that the VTA
        // will have an actor (ie, self) in 'sender' to send replies to.
    }

    override def receive = LoggingReceive {

        case DatapathReady(dp, state) if null == datapath =>
            datapath = dp
            dpState = state

        case HandlePackets(packets) =>
            var i = 0
            while (i < packets.length && packets(i) != null) {
                try {
                    handlePacket(packets(i))
                } catch {
                    case e: Exception =>
                        log.error(e, "Packet workflow crashed for {}", packets(i))
                }
                i += 1
            }

        case RestartWorkflow(pw) if pw.idle =>
            metrics.packetsOnHold.dec()
            log.debug("Restarting workflow for {}", pw.cookieStr)
            try {
                startWorkflow(pw)
            } catch {
                case e: Exception =>
                    log.error(e, "Packet workflow crashed for {}", pw.packet)
            }

        case ApplyFlow(actions, cookieOpt) if cookieOpt.isDefined =>
            val cookie = cookieOpt.get
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

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            startWorkflow(workflow(Packet.fromEthernet(ethernet),
                                   Right(egressPort)))

        case TraceConditions(newTraceConditions) =>
            log.debug("traceConditions updated to {}", newTraceConditions)
            traceConditions = newTraceConditions
    }

    private def giveUpWorkflows(pws: Iterable[PacketHandler]) {
        for (pw <- pws if pw.idle)
            giveUpWorkflow(pw)
    }

    private def giveUpWorkflow(pw: PacketHandler) {
        try {
            pw.drop()
        } catch {
            case e: Exception =>
                log.error(e, "Failed to drop flow for {}", pw.packet)
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
            val expiry = Platform.currentTime + simulationExpireMillis
            new Coordinator(wcMatch, packet.getPacket, cookie, egressPort,
                expiry, connectionCache, traceMessageCache, traceIndexCache,
                parentCookie, traceConditions).simulate()
        }
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pw: PacketHandler, f: Future[_]) {
        log.debug("Packet with {} postponed", pw.cookieStr)
        packetOut()
        f onComplete {
            case Success(_) =>
                log.info("Issuing restart for simulation {}", pw.cookieStr)
                self ! RestartWorkflow(pw)
            case Failure(ex) =>
                log.warning("Failure on waiting workflow's future", ex)
        }
        metrics.packetPostponed()
        waitingRoom enter pw
    }

    /**
     * Deal with a completed workflow
     */
    private def complete(path: PipelinePath, pw: PacketHandler) {
        log.debug("Packet with {} processed", pw.cookieStr)
        pw.cookie match {
            case Some(c) =>
                // TODO: use the PacketContext to know if a simulation has
                // been restarted or not, and conditionally call packetOut
                packetOut()
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
    }

    /**
     * Handles an error in a workflow execution.
     */
    private def handleErrorOn(pw: PacketHandler, ex: Exception) {
        log.warning("Exception while processing packet {} - {}, {}",
                    pw.cookieStr, ex.getMessage, ex.getStackTraceString)
        giveUpWorkflow(pw)
    }

    protected def startWorkflow(pw: PacketHandler) {
        try {
            pw.start() match {
                case Ready(path) => complete(path, pw)
                case NotYet(f) => postponeOn(pw, f)
            }
        } catch {
            case ex: Exception => handleErrorOn(pw, ex)
        }
    }

    private def handlePacket(packet: Packet) {
        val flowMatch = packet.getMatch
        log.debug("Handling packet with match {}", flowMatch)
        dpMatchToCookie.get(flowMatch) match {
            case None =>
                // If there is no entry for the flow match, create a new
                // cookie and a new PacketWorkflow to handle the packet
                val newCookie = cookieGen.next
                log.debug("new cookie #{} for new match {}", newCookie, flowMatch)
                dpMatchToCookie.put(flowMatch, newCookie)
                cookieToDpMatch.put(newCookie, flowMatch)
                cookieToPendedPackets.put(newCookie, mutable.Set.empty)
                startWorkflow(workflow(packet, Left(newCookie)))

            case Some(cookie) =>
                // Simulation in progress. Just pend the packet.
                log.debug("A matching packet with cookie {} is already " +
                    "being handled", cookie)
                cookieToPendedPackets.addBinding(cookie, packet)
                metrics.pendedPackets.inc()
        }
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
