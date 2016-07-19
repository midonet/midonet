/*
 * Copyright 2016 Midokura SARL
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

import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, TimeUnit}
import scala.collection.IndexedSeq
import scala.concurrent.duration._

import akka.actor.ActorSystem

import com.google.inject.Inject;
import com.google.common.util.concurrent.AbstractService

import com.codahale.metrics.MetricRegistry

import com.lmax.disruptor._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.DataClient
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.monitoring.FlowRecorder
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{FlowStateStorageFactory, NatBlockAllocator, NatLeaser}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.util.StatisticalCounter
import org.midonet.util.concurrent.NanoClock

abstract class PacketWorkersService extends AbstractService {
    def workers: IndexedSeq[PacketWorker]

    def initWithDatapath(msg: DatapathController.DatapathReady): Unit
}

class PacketWorkersServiceImpl(config: MidolmanConfig,
                               dpChannel: DatapathChannel,
                               flowProcessor: FlowProcessor,
                               flowInvalidator: FlowInvalidator,
                               clusterDataClient: DataClient,
                               natBlockAllocator: NatBlockAllocator,
                               storageFactory: FlowStateStorageFactory,
                               backChannel: ShardedSimulationBackChannel,
                               clock: NanoClock,
                               flowRecorder: FlowRecorder,
                               metricsRegistry: MetricRegistry,
                               counter: StatisticalCounter,
                               actorSystem: ActorSystem)
        extends PacketWorkersService with Runnable with MidolmanLogging {

    override def logSource = "org.midonet.packet-worker.packet-worker-supervisor"

    val numWorkers = {
        val n = config.simulationThreads
        if (n <= 0)
            1
        else if (n > 16)
            16
        else
            n
    }

    val connTrackStateTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](clock)
    val natStateTable = new ShardedFlowStateTable[NatKey, NatBinding](clock)
    val natLeaser: NatLeaser = new NatLeaser {
        val log: Logger = Logger(LoggerFactory.getLogger(classOf[NatLeaser]))
        val allocator = natBlockAllocator
        val clock = PacketWorkersServiceImpl.this.clock
    }
    val traceStateTable = new ShardedFlowStateTable[TraceKey, TraceContext](clock)
    val initQ = new ArrayBlockingQueue[DatapathController.DatapathReady](1)

    val supervisorThread = new Thread(this, "packet-worker-supervisor")
    supervisorThread.setDaemon(true)

    val shutdownLatch = new CountDownLatch(1)

    val workers: IndexedSeq[DisruptorPacketWorker] =
        0 until numWorkers map createWorker

    override def doStart(): Unit = {
        supervisorThread.start()
    }

    override def doStop(): Unit = {
        shutdownLatch.countDown()
    }

    override def run(): Unit = {
        workers foreach { w => w.start() }

        notifyStarted()

        var msg = initQ.poll(1, TimeUnit.SECONDS)
        while (msg == null && shutdownLatch.getCount > 0) {
            msg = initQ.poll(1, TimeUnit.SECONDS)
        }
        if (msg != null) {
            dpChannel.start(msg.datapath)

            backChannel.tell(msg) // tell all workers

            shutdownLatch.await()
        }

        workers foreach { w => w.shutdown() }

        var shutdownGracePeriod = 5000
        while (workers.exists({ w => w.isRunning }) && shutdownGracePeriod >= 0) {
            Thread.sleep(100)
            shutdownGracePeriod -= 100
        }
        workers filter { w => w.isRunning } foreach {
            w => {
                log.error(s"Worker $w didn't shutdown gracefully, killing")
                w.shutdownNow()
            }
        }
        notifyStopped()
    }

    override def initWithDatapath(msg: DatapathController.DatapathReady): Unit = {
        initQ.add(msg)
    }

    private def shardLogger(t: AnyRef) =
        Logger(LoggerFactory.getLogger("org.midonet.state.table"))

    protected def createWorker(index: Int): DisruptorPacketWorker = {
        val cookieGen = new CookieGenerator(index, numWorkers)
        val connTrackShard = connTrackStateTable.addShard(
            log = shardLogger(connTrackStateTable))
        val natShard = natStateTable.addShard(
            log = shardLogger(natStateTable))
        val traceShard = traceStateTable.addShard(
            log = shardLogger(traceStateTable))

        val backChannelProcessor = backChannel.registerProcessor()
        val storage = storageFactory.create()
        val metrics = new PacketPipelineMetrics(metricsRegistry, index)
        val workflow = new PacketWorkflow(
            index, config, cookieGen,
            clock, dpChannel, clusterDataClient,
            flowInvalidator, flowProcessor,
            connTrackShard, natShard, traceShard,
            storage, natLeaser, metrics, flowRecorder,
            counter.addAndGet(index, _: Int),
            backChannelProcessor, actorSystem)

        new DisruptorPacketWorker(workflow, metrics, index)
    }
}

class PacketWorkersServiceDCListener extends DatapathControllerListener {
    @Inject
    val workersService: PacketWorkersService = null

    override def ready(msg: DatapathController.DatapathReady): Unit = {
        workersService.initWithDatapath(msg)
    }
}
