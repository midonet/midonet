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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.collection.IndexedSeq
import scala.concurrent.Future

import akka.actor.ActorSystem

import com.codahale.metrics.MetricRegistry
import com.google.inject.Injector
import com.lmax.disruptor.{RingBuffer, SequenceBarrier}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{FlowStateStorage, MidonetBackendConfig}
import org.midonet.insights.Insights
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath.{FlowProcessor, _}
import org.midonet.midolman.flows.NativeFlowMatchList
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.services.{QosService, TcRequestHandler}
import org.midonet.midolman.io._
import org.midonet.midolman.logging.FlowTracingAppender
import org.midonet.midolman.services.{HostIdProvider, MidolmanActorsService, SelectLoopService}
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MockNetlinkChannelFactory
import org.midonet.midolman.util.mock._
import org.midonet.midolman.vpp.VppController
import org.midonet.netlink.NetlinkChannelFactory
import org.midonet.odp.family.{DatapathFamily, FlowFamily, PacketFamily, PortFamily}
import org.midonet.odp.{Datapath, Flow, FlowMatch, OvsNetlinkFamilies}
import org.midonet.util._
import org.midonet.util.concurrent.{SameThreadButAfterExecutorService, _}
import org.midonet.util.eventloop.{MockSelectLoop, Reactor, SelectLoop}

class MockMidolmanModule(override val hostId: UUID,
                         injector: Injector,
                         config: MidolmanConfig,
                         backend: MidonetBackend,
                         backendConfig: MidonetBackendConfig,
                         directoryReactor: Reactor,
                         actorService: MidolmanActorsService)
        extends MidolmanModule(config, backend, backendConfig, directoryReactor,
                               new MetricRegistry,
                               new MockFlowTablePreallocation(config)) {

    val flowsTable = new ConcurrentHashMap[FlowMatch, Flow]

    protected override def bindSelectLoopService(): Unit = {
        bind(classOf[SelectLoop])
            .annotatedWith(classOf[SelectLoopService.ZEBRA_SERVER_LOOP])
            .to(classOf[MockSelectLoop])
        bind(classOf[SelectLoopService])
            .toInstance(new SelectLoopService {
               override def doStart(): Unit = notifyStarted()
               override def doStop(): Unit = notifyStopped()
        })
    }

    class MockQosService extends QosService(null, hostId, null) {
        // a no-op qos service.
        override def start(): Unit = {}
        override def stop(): Unit = {}
    }

    class MockVppController(datapathState: DatapathState,
                            vt: VirtualTopology)
        extends VppController(hostId, datapathState, null, vt) {

        protected override def doStart(): Unit = notifyStarted()

        protected override def doStop(): Unit = notifyStopped()
    }

    protected override def qosService(
        scanner: InterfaceScanner,
        hostId: UUID,
        tcRequestHandler: TcRequestHandler): QosService = new MockQosService()

    protected override def natAllocator() =
        new MockNatBlockAllocator

    protected override def virtualTopology(simBackChannel: SimulationBackChannel) = {
        val threadId = Thread.currentThread().getId
        new VirtualTopology(
            backend,
            config,
            simBackChannel,
            new MockRuleLogEventChannel,
            new MetricRegistry,
            new SameThreadButAfterExecutorService,
            new SameThreadButAfterExecutorService,
            () => threadId == Thread.currentThread().getId,
            cbRegistry)
    }

    protected override def flowTracingAppender()=
        new FlowTracingAppender(Future.failed(new Exception))

    protected override def bindHostService(): Unit = { }

    protected override def flowStateStorageFactory(): FlowStateStorageFactory =
        new FlowStateStorageFactory() {
            override def create(): Future[FlowStateStorage[ConnTrackKey, NatKey]] =
                Future.successful(new MockStateStorage())
        }

    protected override def createPacketWorkersService(config: MidolmanConfig,
                                                      hostIdProvider: HostIdProvider,
                                                      dpChannel: DatapathChannel,
                                                      dpState: DatapathState,
                                                      flowProcessor: FlowProcessor,
                                                      natBlockAllocator: NatBlockAllocator,
                                                      peerResolver: PeerResolver,
                                                      backChannel: ShardedSimulationBackChannel,
                                                      vt: VirtualTopology,
                                                      clock: NanoClock,
                                                      backend: MidonetBackend,
                                                      metricsRegistry: MetricRegistry,
                                                      insights: Insights,
                                                      counter: StatisticalCounter,
                                                      actorSystem: ActorSystem,
                                                      preallocation: FlowTablePreallocation)
            : PacketWorkersService =
        new PacketWorkersService() {
            override def workers: IndexedSeq[PacketWorker] = IndexedSeq()
            override def doStart(): Unit = notifyStarted()
            override def doStop(): Unit = notifyStopped()

        }

    protected override def netlinkChannelFactory(): NetlinkChannelFactory =
        new MockNetlinkChannelFactory

    protected override def upcallDatapathConnectionManager(
            tbPolicy: TokenBucketPolicy, workers: IndexedSeq[PacketWorker]) =
        new MockUpcallDatapathConnectionManager(config)

    protected override def datapath(channelFactory: NetlinkChannelFactory,
                                    families: OvsNetlinkFamilies,
                                    metrics: MetricRegistry) =
        new Datapath(0, "midonet")

    protected override def reclaimFlows(dp: Datapath,
                               config: MidolmanConfig,
                               metric: MetricRegistry) = {
        new NativeFlowMatchList()
    }

    protected override def datapathStateDriver(datapath: Datapath) =
        new DatapathStateDriver(datapath)

    protected override def connectionPool(): DatapathConnectionPool =
        new MockDatapathConnectionPool()

    protected override def flowProcessor(
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory,
            backChannel: SimulationBackChannel) =
        new MockFlowProcessor(flowsTable)

    protected override def datapathChannel(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory) =
        new MockDatapathChannel(flowsTable)

    protected override def bindActorService(): Unit =
        bind(classOf[MidolmanActorsService]).toInstance(actorService)

    protected override def interfaceScanner(channelFactory: NetlinkChannelFactory): InterfaceScanner =
        new MockInterfaceScanner

    protected override def ovsNetlinkFamilies(channelFactory: NetlinkChannelFactory) =
        new OvsNetlinkFamilies(new DatapathFamily(0),
                               new PortFamily(0),
                               new FlowFamily(0),
                               new PacketFamily(0), 0, 0)

    protected override def vppController(hostId: UUID,
                                         datapathState: DatapathState,
                                         vt: VirtualTopology): VppController =
        new MockVppController(datapathState, vt)

    protected override def actorSystem() =
        ActorSystem.create("MidolmanActors", ConfigFactory.load()
            .getConfig("midolman")
            .withValue("akka.scheduler.implementation",
                       ConfigValueFactory.fromAnyRef(classOf[MockScheduler].getName)))
}
