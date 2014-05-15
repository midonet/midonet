// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import java.util.UUID
import java.util.{HashMap => JHashMap, List => JList}
import scala.collection.{immutable, mutable}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.concurrent.ExecutionContextOps

object DeduplicationActor {
    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

    case class RestartWorkflow(pw: PacketHandler)

    // This class holds a cache of actions we use to apply the result of a
    // simulation to pending packets while that result isn't written into
    // the WildcardFlowTable. After updating the table, the FlowController
    // will place the FlowMatch in the pending ring buffer so the DDA can
    // evict the entry from the cache.
    sealed class ActionsCache(var size: Int = 256) {
        size = findNextPowerOfTwo(size)
        private val mask = size - 1
        val actions = new JHashMap[FlowMatch, JList[FlowAction]]()
        val pending = new Array[FlowMatch](size)
        var free = 0L
        var expecting = 0L

        def clearProcessedFlowMatches(): Int = {
            var cleared = 0
            while ((free - expecting) > 0) {
                val idx = index(expecting)
                val flowMatch = pending(idx)
                if (flowMatch == null)
                    return cleared

                actions.remove(flowMatch)
                pending(idx) = null
                expecting += 1
                cleared += 1
            }
            cleared
        }

        def getSlot(): Int = {
            val res = free
            if (res - expecting == size) {
                var retries = 200
                while (clearProcessedFlowMatches() == 0) {
                    if (retries > 100) {
                        retries -= 1
                    } else if (retries > 0) {
                        retries -= 1
                        Thread.`yield`()
                    } else {
                        Thread.sleep(0)
                    }
                }
            }
            free += 1
            index(res)
        }

        private def index(x: Long): Int = (x & mask).asInstanceOf[Int]

        private def findNextPowerOfTwo(value: Int) =
            1 << (32 - Integer.numberOfLeadingZeros(value - 1))
    }
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
            val packetOut: Int => Unit)
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
                                        (simulationExpireMillis millis).toNanos)

    protected val actionsCache = new ActionsCache()

    override def receive = LoggingReceive {

        case DatapathReady(dp, state) if null == datapath =>
            datapath = dp
            dpState = state

        case HandlePackets(packets) =>
            actionsCache.clearProcessedFlowMatches()

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

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            startWorkflow(workflow(Packet.fromEthernet(ethernet),
                                   Right(egressPort)))

        case TraceConditions(newTraceConditions) =>
            log.debug("traceConditions updated to {}", newTraceConditions)
            traceConditions = newTraceConditions
    }

    // We return collection.Set so we can return an empty immutable set
    // and a non-empty mutable set.
    private def removePendingPacket(cookie: Int): collection.Set[Packet] = {
        val pending = cookieToPendedPackets.remove(cookie)
        log.debug("Remove {} pending packet(s) for cookie {}",
                  if (pending.isDefined) pending.get.size else 0,
                  cookie)
        if (pending.isDefined) {
            val dpMatch = cookieToDpMatch.remove(cookie)
            if (dpMatch.isDefined)
                dpMatchToCookie.remove(dpMatch.get)
            pending.get
        } else {
            Set.empty
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

        PacketWorkflow(datapathConn(packet), dpState, datapath,
                       clusterDataClient, actionsCache, packet, wcMatch,
                       cookieOrEgressPort, parentCookie)
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
        f.onComplete {
            case Success(_) =>
                log.info("Issuing restart for simulation {}", pw.cookieStr)
                self ! RestartWorkflow(pw)
            case Failure(ex) =>
                log.error(ex, "Failure on waiting workflow's future")
        }(ExecutionContext.callingThread)
        metrics.packetPostponed()
        giveUpWorkflows(waitingRoom enter pw)
    }

    private def giveUpWorkflows(pws: IndexedSeq[PacketHandler]) {
        var i = 0
        while (i < pws.size) {
            val workflow = pws(i)
            if (workflow.idle)
                giveUpWorkflow(workflow)
            else
                log.warning("Pending PacketWorkflow {} was scheduled " +
                            "for cleanup but was not idle", workflow)
            i += 1
        }
    }

    private def giveUpWorkflow(pw: PacketHandler) {
        try {
            pw.drop()
        } catch {
            case e: Exception =>
                log.error(e, "Failed to drop flow for {}", pw.packet)
        } finally {
            var dropped = 0
            if (pw.cookie.isDefined) {
                dropped = removePendingPacket(pw.cookie.get).size
                packetOut(dropped)
            }
            metrics.packetsDropped.mark(dropped + 1)
        }
    }

    /**
     * Deal with a completed workflow
     */
    private def complete(path: PipelinePath, pw: PacketHandler) {
        log.debug("Packet with {} processed", pw.cookieStr)
        waitingRoom leave pw
        pw.cookie match {
            case Some(c) =>
                applyFlow(c, pw)

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

    private def applyFlow(cookie: Int, pw: PacketHandler): Unit = {
        val actions = actionsCache.actions.get(pw.packet.getMatch)
        val pendingPackets = removePendingPacket(cookie)
        val numPendingPackets = pendingPackets.size
        if (numPendingPackets > 0) {
            // Send all pended packets with the same action list (unless
            // the action list is empty, which is equivalent to dropping)
            if (actions.isEmpty) {
                metrics.packetsProcessed.mark(numPendingPackets)
            } else {
                log.debug("Sending pended packets {} for cookie {}",
                          pendingPackets, cookie)
                pendingPackets foreach (executePacket(_, actions))
                metrics.pendedPackets.dec(numPendingPackets)
            }
            packetOut(numPendingPackets)
        }

        if (actions.isEmpty) {
            system.eventStream.publish(DiscardPacket(cookie))
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
        } finally {
            if (pw.runs == 1 && pw.cookie.isDefined)
                packetOut(1)
        }
    }

    private def handlePacket(packet: Packet) {
        val flowMatch = packet.getMatch
        log.debug("Handling packet with match {}", flowMatch)
        val actions = actionsCache.actions.get(flowMatch)
        if (actions != null) {
            log.debug("Got actions from the cache: {}", actions)
            executePacket(packet, actions)
            packetOut(1)
        } else {
            dpMatchToCookie.get(flowMatch) match {
                case None =>
                    // If there is no entry for the flow match, create a new
                    // cookie and a new PacketWorkflow to handle the packet
                    val newCookie = cookieGen.next
                    log.debug("new cookie #{} for new match {}",
                              newCookie, flowMatch)
                    dpMatchToCookie.put(flowMatch, newCookie)
                    cookieToDpMatch.put(newCookie, flowMatch)
                    cookieToPendedPackets.put(newCookie, mutable.Set.empty)
                    startWorkflow(workflow(packet, Left(newCookie)))

                case Some(cookie) =>
                    // Simulation in progress. Just pend the packet.
                    log.debug("A matching packet with cookie {} is already " +
                              "being handled", cookie)
                    cookieToPendedPackets.addBinding(cookie, packet)
                    giveUpWorkflows(waitingRoom.doExpirations())
            }
        }
    }

    private def executePacket(packet: Packet, actions: JList[FlowAction]) {

        val finalActions = if (packet.getMatch.isUserSpaceOnly) {
            UserspaceFlowActionTranslator.translate(packet, actions)
        } else {
            actions
        }

        if (finalActions.isEmpty) {
            return
        }

        try {
            datapathConn(packet).packetsExecute(datapath, packet, finalActions)
        } catch {
            case e: NetlinkException =>
                log.info("Failed to execute packet: {}", e)
        }
        metrics.packetsProcessed.mark()
    }
}
