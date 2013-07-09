// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import akka.actor._
import akka.event.LoggingReceive
import collection.{immutable, mutable}
import scala.collection.JavaConverters._
import collection.mutable.{HashMap, MultiMap}
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.Nullable
import javax.inject.Inject

import com.yammer.metrics.core.{Gauge, MetricsRegistry, Clock}

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.guice.datapath.DatapathModule.SIMULATION_THROTTLING_GUARD
import org.midonet.midolman.guice.CacheModule.{NAT_CACHE, TRACE_MESSAGES,
                                               TRACE_INDEX}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.PacketPipelineMeter
import org.midonet.midolman.monitoring.metrics.PacketPipelineHistogram
import org.midonet.midolman.monitoring.metrics.PacketPipelineGauge
import org.midonet.midolman.monitoring.metrics.PacketPipelineCounter
import org.midonet.midolman.monitoring.metrics.PacketPipelineAccumulatedTime
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.TraceConditionsManager
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.odp.flows.FlowAction
import org.midonet.packets.Ethernet
import org.midonet.util.throttling.{ThrottlingGuard, ThrottlingGuardFactory}


object DeduplicationActor extends Referenceable {
    override val Name = "DeduplicationActor"

    // Messages
    case class HandlePacket(packet: Packet)

    case class ApplyFlow(actions: Seq[FlowAction[_]], cookie: Option[Int])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)


    class PacketPipelineMetrics(val registry: MetricsRegistry,
                                val throttler: ThrottlingGuard) {
        val pendedPackets = registry.newCounter(
            classOf[PacketPipelineGauge], "currentPendedPackets")

        val wildcardTableHits = registry.newMeter(
            classOf[PacketPipelineMeter],
            "wildcardTableHits", "packets",
            TimeUnit.SECONDS)

        val packetsToPortSet = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsToPortSet", "packets",
            TimeUnit.SECONDS)

        val packetsSimulated = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsSimulated", "packets",
            TimeUnit.SECONDS)

        val packetsProcessed = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsProcessed", "packets",
            TimeUnit.SECONDS)

        // FIXME(guillermo) - make this a meter - the throttler needs to expose a callback
        val packetsDropped = registry.newGauge(
            classOf[PacketPipelineCounter],
            "packetsDropped",
            new Gauge[Long]{
                override def value = throttler.numDroppedTokens()
            })

        val liveSimulations = registry.newGauge(
            classOf[PacketPipelineGauge],
            "liveSimulations",
            new Gauge[Long]{
                override def value = throttler.numTokens()
            })

        val wildcardTableHitLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "wildcardTableHitLatency")

        val packetToPortSetLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "packetToPortSetLatency")

        val simulationLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "simulationLatency")

        val wildcardTableHitAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "wildcardTableHitAccumulatedTime")

        val packetToPortSetAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "packetToPortSetAccumulatedTime")

        val simulationAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "simulationAccumulatedTime")


        def wildcardTableHit(latency: Int) {
            wildcardTableHits.mark()
            wildcardTableHitLatency.update(latency)
            wildcardTableHitAccumulatedTime.inc(latency)
        }

        def packetToPortSet(latency: Int) {
            packetsToPortSet.mark()
            packetToPortSetLatency.update(latency)
            packetToPortSetAccumulatedTime.inc(latency)
        }

        def packetSimulated(latency: Int) {
            packetsSimulated.mark()
            simulationLatency.update(latency)
            simulationAccumulatedTime.inc(latency)
        }
    }
}


class DeduplicationActor extends Actor with ActorLogWithoutPath with
        UserspaceFlowActionTranslator {

    import DeduplicationActor._

    @Inject var datapathConnection: OvsDatapathConnection = null
    @Inject var clusterDataClient: DataClient = null
    @Inject @Nullable @NAT_CACHE var connectionCache: Cache = null
    @Inject @TRACE_MESSAGES var traceMessageCache: Cache = null
    @Inject @TRACE_INDEX var traceIndexCache: Cache = null
    var traceConditions = immutable.Seq[Condition]()

    @Inject
    @SIMULATION_THROTTLING_GUARD
    var throttler: ThrottlingGuard = null

    @Inject
    var metricsRegistry: MetricsRegistry = null

    var datapath: Datapath = null
    var dpState: DatapathState = null
    val idGenerator: AtomicInteger = new AtomicInteger(0)

    // data structures to handle the duplicate packets.
    private val dpMatchToCookie = HashMap[FlowMatch, Int]()
    private val cookieToPendedPackets: MultiMap[Int, Packet] =
        new HashMap[Int, mutable.Set[Packet]] with MultiMap[Int, Packet]

    var metrics: PacketPipelineMetrics = null

    override def preStart() {
        super.preStart()
        metrics = new PacketPipelineMetrics(metricsRegistry, throttler)
        VirtualTopologyActor.getRef().tell(
            VirtualTopologyActor.ConditionListRequest(
                TraceConditionsManager.uuid, true))
    }

    def receive = LoggingReceive {
        case DatapathController.DatapathReady(dp, state) =>
            if (null == datapath) {
                datapath = dp
                dpState = state
                installPacketInHook()
                log.info("Datapath hook installed")
            }

        case HandlePacket(packet) => {
            dpMatchToCookie.get(packet.getMatch) match {
            case None =>
                val cookie: Int = idGenerator.getAndIncrement

                cookieToPendedPackets.addBinding(cookie, packet)
                // If there is no match on the cookie, create an object to
                // handle the packet.
                val packetWorkflow = new PacketWorkflow(
                        datapathConnection, dpState, datapath,
                        clusterDataClient, connectionCache, traceMessageCache,
                        traceIndexCache, packet, Left(cookie), throttler,
                        metrics, traceConditions)(this.context.dispatcher,
                        this.context.system, this.context)

                log.debug("Created new {} packet handler.", "PacketWorkflow-" + cookie)
                context.dispatcher.execute{new Runnable {
                        override def run() { packetWorkflow.start() }
                    }
                }

            case Some(cookie: Int) =>
                log.debug("A matching packet with cookie {} is already being handled ", cookie)
                // Simulation in progress. Just pend the packet.
                throttler.tokenOut()
                cookieToPendedPackets.addBinding(cookie, packet)
                metrics.pendedPackets.inc()
            }
        }

        case ApplyFlow(actions, cookieOpt) => cookieOpt foreach { cookie =>
            cookieToPendedPackets.remove(cookie) foreach { pendedPackets =>
                val packet = pendedPackets.last
                dpMatchToCookie.remove(packet.getMatch)

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (actions.length > 0) {
                    for (unpendedPacket <- pendedPackets.tail) {
                        executePacket(cookie, packet, actions)
                        metrics.pendedPackets.dec()
                    }
                } else {
                    metrics.packetsProcessed.mark(pendedPackets.tail.size)
                }
            }

            if (actions.length == 0) {
                context.system.eventStream.publish(DiscardPacket(cookie))
            }
        }

        // This creates a new PacketWorkflowActor and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            val packet = new Packet().setPacket(ethernet)
            val packetId = scala.util.Random.nextLong()
            val packetWorkflow =
                new PacketWorkflow(datapathConnection, dpState,
                    datapath, clusterDataClient, connectionCache,
                    traceMessageCache, traceIndexCache, packet,
                    Right(egressPort), throttler, metrics, traceConditions)(
                    this.context.dispatcher, this.context.system, this.context)

            log.debug("Created new {} handler.",
                      "PacketWorkflow-generated-" + packetId)
            packetWorkflow.start()

        case newTraceConditions: immutable.Seq[Condition] =>
            traceConditions = newTraceConditions
    }

    private def executePacket(cookie: Int,
                              packet: Packet,
                              actions: Seq[FlowAction[_]]) {
        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly)
            applyActionsAfterUserspaceMatch(packet)

        if (packet.getActions.size > 0) {
            log.debug("Sending pended packet {} for cookie {}",
                packet, cookie)

            datapathConnection.packetsExecute(datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {
                        metrics.packetsProcessed.mark()
                    }

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} due to {}",
                            packet,
                            if (timeout) "timeout" else "error")
                        metrics.packetsProcessed.mark()
                    }
                })
        }
    }

    private def installPacketInHook() = {
         log.info("Installing packet in handler in the DDA")
         datapathConnection.datapathsSetNotificationHandler(datapath,
                 new Callback[Packet] {
                     def onSuccess(data: Packet) {
                         val eth = data.getPacket
                         FlowMatches.addUserspaceKeys(eth, data.getMatch)
                         data.setStartTimeNanos(Clock.defaultClock().tick())
                         self ! HandlePacket(data)
                     }

                 def onTimeout() {}

                 def onError(e: NetlinkException) {}
         }).get()
    }

}
