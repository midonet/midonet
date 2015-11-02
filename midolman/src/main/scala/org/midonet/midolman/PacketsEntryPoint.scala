/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman

import scala.collection.immutable
import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props, _}
import akka.event.LoggingReceive
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.FlowRecorder
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.simulation.DhcpConfigFromNsdb
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.topology.RouterMapper.InvalidateFlows
import org.midonet.midolman.state.{PeerResolver, FlowStateStorageFactory, NatBlockAllocator, NatLeaser}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.util.StatisticalCounter
import org.midonet.util.concurrent.NanoClock

object PacketsEntryPoint extends Referenceable {
    override val Name = "PacketsEntryPoint"

    case object GetWorkers

    case class Workers(list: IndexedSeq[ActorRef])
}

class PacketsEntryPoint extends Actor with ActorLogWithoutPath {
    import org.midonet.midolman.PacketsEntryPoint._

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
    implicit val ec = context.dispatcher

    @Inject
    var dpChannel: DatapathChannel = _

    @Inject
    var vt: VirtualTopology = null

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    var metrics: PacketPipelineMetrics = null

    protected var workers = immutable.IndexedSeq[ActorRef]()

    @Inject
    var counter: StatisticalCounter = null

    @Inject
    var storageFactory: FlowStateStorageFactory = null

    @Inject
    var clock: NanoClock = null

    @Inject
    var backChannel: ShardedSimulationBackChannel = null

    @Inject
    var flowProcessor: FlowProcessor = null

    @Inject
    var natBlockAllocator: NatBlockAllocator = _

    @Inject
    var dpState: DatapathState = _

    @Inject
    var hostIdProvider: HostIdProvider = _

    @Inject
    var flowRecorder: FlowRecorder = _

    @Inject
    var peerResolver: PeerResolver = _

    var connTrackStateTable: ShardedFlowStateTable[ConnTrackKey, ConnTrackValue] = _
    var natStateTable: ShardedFlowStateTable[NatKey, NatBinding] = _
    var natLeaser: NatLeaser = _
    var traceStateTable: ShardedFlowStateTable[TraceKey, TraceContext] = _

    override def preStart(): Unit = {
        super.preStart()
        NUM_WORKERS = config.simulationThreads

        connTrackStateTable = new ShardedFlowStateTable(clock)
        natStateTable = new ShardedFlowStateTable(clock)
        natLeaser = new NatLeaser {
            val log: Logger = Logger(LoggerFactory.getLogger(classOf[NatLeaser]))
            val allocator = natBlockAllocator
            val clock = PacketsEntryPoint.this.clock
        }
        traceStateTable = new ShardedFlowStateTable(clock)

        for (i <- 0 until NUM_WORKERS) {
            workers :+= startWorker(i)
        }
        workers.awaitStart(30 seconds)
        dpChannel.start()
    }

    override def postStop(): Unit = {
        dpChannel.stop()
    }

    private def shardLogger(t: AnyRef) =
        Logger(LoggerFactory.getLogger("org.midonet.state.table"))

    protected def startWorker(index: Int): ActorRef = {
        val props = propsForWorker(index).withDispatcher("actors.pinned-dispatcher")
        context.actorOf(props, s"PacketProcessor-$index")
    }

    protected def propsForWorker(index: Int) = {
        val cookieGen = new CookieGenerator(index, NUM_WORKERS)
        val dhcpConfig = new DhcpConfigFromNsdb(vt)

        Props(new PacketWorkflow(
            config,
            hostIdProvider.hostId,
            dpState,
            cookieGen,
            clock,
            dpChannel,
            dhcpConfig,
            backChannel.registerProcessor(),
            flowProcessor,
            connTrackStateTable.addShard(log = shardLogger(connTrackStateTable)),
            natStateTable.addShard(log = shardLogger(natStateTable)),
            traceStateTable.addShard(log = shardLogger(traceStateTable)),
            peerResolver,
            storageFactory.create(),
            natLeaser,
            metrics,
            flowRecorder,
            counter.addAndGet(index, _: Int)))
    }

    private def broadcast(m: Any) { workers foreach ( _ ! m ) }

    override def receive = LoggingReceive {
        case m: InvalidateFlows => broadcast(m)

        case m: FlowStateBatch => broadcast(m)

        case CheckBackchannels => broadcast(CheckBackchannels)

        case GetWorkers => sender ! Workers(workers)
    }
}
