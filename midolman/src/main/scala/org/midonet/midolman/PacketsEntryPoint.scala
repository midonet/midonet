/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import scala.collection.immutable

import akka.actor._
import akka.event.LoggingReceive
import com.yammer.metrics.core.MetricsRegistry
import javax.annotation.Nullable
import javax.inject.Inject

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.guice.CacheModule.NAT_CACHE
import org.midonet.midolman.guice.CacheModule.TRACE_INDEX
import org.midonet.midolman.guice.CacheModule.TRACE_MESSAGES
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.topology.TraceConditionsManager
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.util.StatisticalCounter

object PacketsEntryPoint extends Referenceable {
    override val Name = "PacketsEntryPoint"

    case object GetWorkers

    case class Workers(list: IndexedSeq[ActorRef])

    case object _GetConditionListFromVta
}

class PacketsEntryPoint extends Actor with ActorLogWithoutPath {

    import DatapathController.DatapathReady
    import DeduplicationActor._
    import PacketsEntryPoint._
    import VirtualTopologyActor.ConditionListRequest

    private var _NUM_WORKERS = 1
    def NUM_WORKERS = _NUM_WORKERS
    private def NUM_WORKERS_=(n: Int) {
        if (n <= 0)
            _NUM_WORKERS = 1
        else if (n > 16)
            _NUM_WORKERS = 16
        else
            _NUM_WORKERS = n
    }

    @Inject
    var config: MidolmanConfig = null

    implicit val as = context.system

    @Inject
    var dpConnPool: DatapathConnectionPool = null

    @Inject
    var clusterDataClient: DataClient = null

    @Inject @Nullable @NAT_CACHE
    var connectionCache: Cache = null
    @Inject @TRACE_MESSAGES
    var traceMessageCache: Cache = null
    @Inject @TRACE_INDEX
    var traceIndexCache: Cache = null

    @Inject
    var metricsRegistry: MetricsRegistry = null

    private var metrics: PacketPipelineMetrics = null

    protected var workers = immutable.IndexedSeq[ActorRef]()
    private var rrIndex = 0

    @Inject
    var counter: StatisticalCounter = null

    override def preStart() {
        super.preStart()
        NUM_WORKERS = config.getSimulationThreads
        metrics = new PacketPipelineMetrics(metricsRegistry)
        // Defer this until actor start-up finishes, so that the VTA
        // will have an actor (ie, self) in 'sender' to send replies to.
        self ! _GetConditionListFromVta

        for (i <- 0 until NUM_WORKERS) {
            workers :+= startWorker(i)
        }
    }

    protected def startWorker(index: Int): ActorRef = {
        val cookieGen = new CookieGenerator(index, NUM_WORKERS)
        val props = Props(classOf[DeduplicationActor],
                            cookieGen, dpConnPool, clusterDataClient,
                            connectionCache, traceMessageCache, traceIndexCache,
                            metrics, () => counter.incAndGet(index))
                    .withDispatcher("actors.pinned-dispatcher")

        context.actorOf(props, s"PacketProcessor-$index")
    }

    private def broadcast(m: Any) { workers foreach ( _ ! m ) }

    private def roundRobin(m: Any) {
        workers(java.lang.Math.abs(rrIndex) % NUM_WORKERS) ! m
        rrIndex += 1
    }

    override def receive = LoggingReceive {

        case m: DatapathReady => broadcast(m)

        case m: TraceConditions => broadcast(m)

        case m: EmitGeneratedPacket => roundRobin(m)

        case GetWorkers => sender ! Workers(workers)

        case PacketsEntryPoint._GetConditionListFromVta =>
            VirtualTopologyActor !
                ConditionListRequest(TraceConditionsManager.uuid, update = true)
    }
}
