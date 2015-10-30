/*
 * Copyright 2015 Midokura SARL
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

import java.util.{UUID, LinkedList}
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import org.midonet.conf.HostIdGenerator

import scala.concurrent.Future

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.AbstractService
import com.google.inject.name.Names
import com.lmax.disruptor.{SequenceBarrier, RingBuffer}
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}

import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io._
import org.midonet.midolman.logging.FlowTracingAppender
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.{MidolmanActorsService, HostIdProvider, SelectLoopService}
import org.midonet.midolman.state._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockFlowProcessor, MockDatapathChannel, MockUpcallDatapathConnectionManager}
import org.midonet.netlink.NetlinkChannelFactory
import org.midonet.odp.{Flow, FlowMatch, Datapath, OvsNetlinkFamilies}
import org.midonet.odp.family.{PacketFamily, FlowFamily, PortFamily, DatapathFamily}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.eventloop.{MockSelectLoop, SelectLoop}
import org.midonet.util.functors.{Predicate, makePredicate}

class MockMidolmanModule(override val hostId: UUID,
                         config: MidolmanConfig,
                         actorService: MidolmanActorsService)
        extends MidolmanModule(config, new MetricRegistry) {

    val flowsTable = new ConcurrentHashMap[FlowMatch, Flow]

    override def simulationBackChannel(as: ActorSystem) = {
        bind(classOf[ShardedSimulationBackChannel]).toInstance(
            new ShardedSimulationBackChannel(() => {}))
        new SimulationBackChannel() {
            private val q = new LinkedList[BackChannelMessage]()
            override def tell(msg: BackChannelMessage): Unit = q.offer(msg)
            override def hasMessages: Boolean = !q.isEmpty
            override def poll(): BackChannelMessage = q.pop()
        }
    }

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

    protected override def bindNatAllocator() {
        bind(classOf[NatBlockAllocator]).toInstance(new MockNatBlockAllocator)
    }

    protected override def bindVirtualTopology() {
        val threadId = Thread.currentThread().getId
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.VtExecutorName))
            .toInstance(new SameThreadButAfterExecutorService())
        bind(classOf[Predicate])
            .annotatedWith(Names.named(VirtualTopology.VtExecutorCheckerName))
            .toInstance(makePredicate(
                            { threadId == Thread.currentThread().getId }))
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named(VirtualTopology.IoExecutorName))
            .toInstance(new SameThreadButAfterExecutorService())

        bind(classOf[VirtualTopology])
            .asEagerSingleton()
    }

    protected override def flowTracingAppender()=
        new FlowTracingAppender(Future.failed(new Exception))

    protected override def bindHostService(): Unit = { }

    protected override def flowStateStorageFactory() =
        new FlowStateStorageFactory() {
            override def create(): Future[FlowStateStorage] =
                Future.successful(new MockStateStorage)
        }

    protected override def upcallDatapathConnectionManager(
            tbPolicy: TokenBucketPolicy) =
        new MockUpcallDatapathConnectionManager(config)

    protected override def datapathStateDriver(
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies) =
        new DatapathStateDriver(new Datapath(0, "midonet"))

    protected override def connectionPool(): DatapathConnectionPool =
        new MockDatapathConnectionPool()

    protected override def flowProcessor(
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory) =
        new MockFlowProcessor(flowsTable)

    protected override def datapathChannel(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory,
            metrics: PacketPipelineMetrics) =
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

    protected override def actorSystem() =
        ActorSystem.create("MidolmanActors", ConfigFactory.load()
            .getConfig("midolman")
            .withValue("akka.scheduler.implementation",
                       ConfigValueFactory.fromAnyRef(classOf[MockScheduler].getName)))
}
