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

import java.nio.channels.spi.SelectorProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import scala.collection.IndexedSeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorSystem, OneForOneStrategy, SupervisorStrategy}

import com.codahale.metrics.MetricRegistry
import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Injector, Key}
import com.lmax.disruptor._
import com.typesafe.config.ConfigFactory

import org.reflections.Reflections
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.Util
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{FlowStateStorage, MidonetBackendConfig}
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath._
import org.midonet.midolman.host.scanner.{DefaultInterfaceScanner, InterfaceScanner}
import org.midonet.midolman.host.services.{HostService, TcRequestHandler, QosService}
import org.midonet.midolman.io._
import org.midonet.midolman.logging.rule.{DisruptorRuleLogEventChannel, RuleLogEventChannel}
import org.midonet.midolman.logging.{FlowTracingAppender, FlowTracingSchema}
import org.midonet.midolman.monitoring.metrics.{DatapathMetrics, PacketExecutorMetrics}
import org.midonet.midolman.openstack.metadata.{DatapathInterface, Plumber}
import org.midonet.midolman.services._
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.netlink.{NetlinkChannelFactory, NetlinkProtocol, NetlinkUtil}
import org.midonet.odp.OvsNetlinkFamilies
import org.midonet.util._
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.{Reactor, SelectLoop, SimpleSelectLoop}

class MidolmanModule(injector: Injector,
                     config: MidolmanConfig,
                     metricRegistry: MetricRegistry,
                     reflections: Reflections) extends AbstractModule {
    private val log: Logger = LoggerFactory.getLogger(classOf[MidolmanModule])

    override def configure(): Unit = {
        bind(classOf[MidolmanConfig]).toInstance(config)
        val host = hostId()
        val hostIdProvider = new HostIdProvider {
            override def hostId(): UUID = host
        }
        bind(classOf[HostIdProvider]).toInstance(hostIdProvider)

        bind(classOf[NanoClock]).toInstance(NanoClock.DEFAULT)
        bind(classOf[UnixClock]).toInstance(UnixClock.DEFAULT)

        bind(classOf[MetricRegistry]).toInstance(metricRegistry)

        // We add an extra slot so that channels can return tokens
        // they obtained due to the multiplier effect but didn't use.
        val counter = new StatisticalCounter(config.simulationThreads + 1)
        val policy = htbPolicy(counter)
        bind(classOf[StatisticalCounter]).toInstance(counter)
        bind(classOf[TokenBucketPolicy]).toInstance(policy)

        val channelFactory = netlinkChannelFactory()
        val families = ovsNetlinkFamilies(channelFactory)
        val dpState = datapathStateDriver(channelFactory, families)
        bind(classOf[NetlinkChannelFactory]).toInstance(channelFactory)
        bind(classOf[OvsNetlinkFamilies]).toInstance(families)
        bind(classOf[DatapathStateDriver]).toInstance(dpState)
        bind(classOf[DatapathState]).to(classOf[DatapathStateDriver])
        bind(classOf[VirtualPortsResolver]).to(classOf[DatapathStateDriver])
        bind(classOf[UnderlayResolver]).to(classOf[DatapathStateDriver])

        val as = actorSystem()
        bind(classOf[ActorSystem]).toInstance(as)
        bind(classOf[SupervisorStrategy]).toInstance(crashStrategy())

        val backChannel = new ShardedSimulationBackChannel
        bind(classOf[ShardedSimulationBackChannel]).toInstance(backChannel)
        bind(classOf[SimulationBackChannel]).toInstance(backChannel)

        val capacity = Util.findNextPositivePowerOfTwo(
            config.datapath.globalIncomingBurstCapacity)
        val ringBuffer = RingBuffer
            .createMultiProducer(DisruptorDatapathChannel.Factory, capacity)
        val barrier = ringBuffer.newBarrier()
        val fp = flowProcessor(dpState, families, channelFactory, backChannel)
        val channel = datapathChannel(
            ringBuffer, barrier, fp, dpState, families, channelFactory)
        bind(classOf[FlowProcessor]).toInstance(fp)
        bind(classOf[DatapathChannel]).toInstance(channel)

        bind(classOf[DatapathConnectionPool]).toInstance(connectionPool())

        bind(classOf[DatapathService]).asEagerSingleton()

        bind(classOf[FlowStateStorageFactory]).toInstance(flowStateStorageFactory())

        bindActorService()

        val scanner = interfaceScanner(channelFactory)
        bind(classOf[InterfaceScanner]).toInstance(scanner)

        val hservice = hostService(host, scanner)
        bind(classOf[HostService]).toInstance(hservice)

        bind(classOf[Plumber]).toInstance(plumber(dpState))

        val tcRequestHandler = TcRequestHandler(channelFactory)
        bind(classOf[TcRequestHandler]).toInstance(tcRequestHandler)

        val qService = qosService(scanner, host, tcRequestHandler)
        bind(classOf[QosService]).toInstance(qService)

        bind(classOf[FlowTracingAppender]).toInstance(flowTracingAppender())

        val allocator = natAllocator()
        bind(classOf[NatBlockAllocator]).toInstance(allocator)
        bindSelectLoopService()

        val vt = virtualTopology(backChannel)
        bind(classOf[VirtualTopology]).toInstance(vt)

        val vtpm = virtualToPhysicalMapper(host, vt)
        bind(classOf[VirtualToPhysicalMapper]).toInstance(vtpm)

        val resolver = peerResolver(host, vt)
        bind(classOf[PeerResolver]).toInstance(resolver)

        val backend = injector.getInstance(classOf[MidonetBackend])
        val workersService = createPacketWorkersService(config, hostIdProvider,
                                                        channel, dpState, fp,
                                                        allocator, resolver,
                                                        backChannel, vt,
                                                        NanoClock.DEFAULT,
                                                        backend,
                                                        metricRegistry,
                                                        counter, as)
        bind(classOf[PacketWorkersService]).toInstance(workersService)

        val dpConnectionManager = upcallDatapathConnectionManager(
            policy, workersService.workers)
        bind(classOf[UpcallDatapathConnectionManager]).toInstance(
            dpConnectionManager)
        bind(classOf[DatapathInterface]).toInstance(
            datapathInterface(scanner, dpState, dpConnectionManager))


        bind(classOf[MidolmanService]).asEagerSingleton()
    }

    protected def hostId() =
        HostIdGenerator.getHostId

    protected def htbPolicy(counter: StatisticalCounter) = {
        val multiplier = 8
        // Here we check whether increments to our slot in the StatisticalCounter
        // should be atomic or not, depending on whether multiple threads will
        // be accessing it (true in the one_to_one" configuration setting).
        val atomic = config.inputChannelThreading match {
            case "one_to_many" => false
            case "one_to_one" => true
            case s => throw new IllegalArgumentException(
                        "Unknown value for input_channel_threading: " + s)
        }

        new TokenBucketPolicy(
            config,
            new TokenBucketSystemRate(counter, multiplier),
            multiplier,
            tb => new Bucket(tb, multiplier, counter, config.simulationThreads, atomic))
    }

    protected def netlinkChannelFactory() =
        new NetlinkChannelFactory

    protected def ovsNetlinkFamilies(channelFactory: NetlinkChannelFactory) = {
        val channel = channelFactory.create(
            blocking = true, NetlinkProtocol.NETLINK_GENERIC, NetlinkUtil.NO_NOTIFICATION)
        try {
            try {
                val families = OvsNetlinkFamilies.discover(channel)
                log.debug(families.toString)
                families
            } finally {
                channel.close()
            }
        }  catch { case e: Exception =>
            throw new RuntimeException(e)
        }
    }

    protected def datapathStateDriver(
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies) =
        DatapathBootstrap.bootstrap(
            config, channelFactory, families)

    protected def flowProcessor(
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory,
            backChannel: SimulationBackChannel) =
        new FlowProcessor(
            dpState,
            families,
            maxPendingRequests = config.datapath.globalIncomingBurstCapacity * 2,
            maxRequestSize = 512,
            channelFactory,
            SelectorProvider.provider,
            backChannel,
            new DatapathMetrics(metricRegistry),
            NanoClock.DEFAULT)

    protected def createProcessors(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory) = {
        val threads = Math.max(config.outputChannels, 1)
        val processors = new Array[EventProcessor](threads)
        if (threads  == 1) {
            val fpHandler = new AggregateEventPollerHandler(
                flowProcessor,
                new EventPollerHandlerAdapter(
                    new PacketExecutor(
                        dpState, families, 1, 0, channelFactory,
                        new PacketExecutorMetrics(metricRegistry, 0))))
            processors(0) = new BackChannelEventProcessor(
                ringBuffer, fpHandler, flowProcessor)
        } else {
            val numPacketHandlers = threads  - 1
            for (i <- 0 until numPacketHandlers) {
                val pexec = new PacketExecutor(
                    dpState, families, numPacketHandlers, i, channelFactory,
                    new PacketExecutorMetrics(metricRegistry, i))
                processors(i) = new BatchEventProcessor(ringBuffer, barrier, pexec)
            }
            processors(numPacketHandlers) = new BackChannelEventProcessor(
                ringBuffer, flowProcessor, flowProcessor)
        }
        processors
    }

    protected def datapathChannel(
            ringBuffer: RingBuffer[PacketContextHolder],
            barrier: SequenceBarrier,
            flowProcessor: FlowProcessor,
            dpState: DatapathState,
            families: OvsNetlinkFamilies,
            channelFactory: NetlinkChannelFactory): DatapathChannel = {
        new DisruptorDatapathChannel(
            ringBuffer,
            createProcessors(
                ringBuffer,
                barrier,
                flowProcessor,
                dpState,
                families,
                channelFactory))
    }

    protected def ruleLogEventChannel(capacity: Int): RuleLogEventChannel = {
        DisruptorRuleLogEventChannel(capacity, config.ruleLogging)
    }

    protected def upcallDatapathConnectionManager(
            tbPolicy: TokenBucketPolicy,
            workers: IndexedSeq[PacketWorker]) =
        config.inputChannelThreading match {
            case "one_to_many" =>
                new OneToManyDpConnManager(config, workers,
                                           tbPolicy, metricRegistry)
            case "one_to_one" =>
                new OneToOneDpConnManager(config, workers,
                                          tbPolicy, metricRegistry)
            case s =>
                throw new IllegalArgumentException(
                    "Unknown value for input_channel_threading: " + s)
        }

    protected def flowStateStorageFactory() = {
        val cass = new CassandraClient(
            config.zookeeper,
            config.cassandra,
            "MidonetFlowState",
            FlowStateStorage.SCHEMA,
            FlowStateStorage.SCHEMA_TABLE_NAMES)
        new FlowStateStorageFactory() {
            override def create(): Future[FlowStateStorage[ConnTrackKey, NatKey]] =
                cass.connect()
                    .map(FlowStateStorage(_, NatKey, ConnTrackKey))(ExecutionContext.callingThread)
        }
    }

    protected def createPacketWorkersService(config: MidolmanConfig,
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
                                             counter: StatisticalCounter,
                                             actorSystem: ActorSystem)
            : PacketWorkersService =
        new PacketWorkersServiceImpl(config, hostIdProvider, dpChannel, dpState,
                                        flowProcessor, natBlockAllocator, peerResolver,
                                        backChannel, vt, clock, backend,
                                        metricsRegistry, counter, actorSystem)

    protected def connectionPool(): DatapathConnectionPool =
        new OneToOneConnectionPool(
            "netlink.requests", config.outputChannels, config, metricRegistry)

    protected def interfaceScanner(channelFactory: NetlinkChannelFactory): InterfaceScanner =
        new DefaultInterfaceScanner(
            channelFactory,
            NetlinkUtil.DEFAULT_MAX_REQUESTS,
            NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
            NanoClock.DEFAULT)

    protected def hostService(
            hostId: UUID,
            interfaceScanner: InterfaceScanner): HostService =
        new HostService(
            config,
            injector.getInstance(classOf[MidonetBackendConfig]),
            injector.getInstance(classOf[MidonetBackend]),
            interfaceScanner,
            hostId,
            injector.getInstance(Key.get(classOf[Reactor],
                                         Names.named("directoryReactor"))))

    protected def qosService(scanner: InterfaceScanner,
                             hostId: UUID,
                             tcRequestHandler: TcRequestHandler): QosService =
        QosService(scanner, hostId, tcRequestHandler)

    protected def bindHostService(): Unit =
        bind(classOf[HostService]).asEagerSingleton()

    protected def datapathInterface(
            scanner: InterfaceScanner,
            dpState: DatapathState,
            dpConnManager: UpcallDatapathConnectionManager) =
        new DatapathInterface(scanner, dpState, dpConnManager)

    protected def plumber(dpState: DatapathState) =
        new Plumber(dpState)

    protected def actorSystem() =
        ActorSystem.create("midolman", ConfigFactory.load().getConfig("midolman"))

    protected def bindActorService(): Unit =
        bind(classOf[MidolmanActorsService]).asEagerSingleton()

    protected def flowTracingAppender() = {
        val cass = new CassandraClient(
            config.zookeeper,
            config.cassandra,
            FlowTracingSchema.KEYSPACE_NAME,
            FlowTracingSchema.SCHEMA,
            FlowTracingSchema.SCHEMA_TABLE_NAMES)
        new FlowTracingAppender(cass.connect())
    }

    protected def natAllocator(): NatBlockAllocator = {
        val backend = injector.getInstance(classOf[MidonetBackend])
        new ZkNatBlockAllocator(backend.curator, UnixClock.DEFAULT)
    }

    protected def bindSelectLoopService(): Unit = {
        bind(classOf[SelectLoop])
            .annotatedWith(classOf[SelectLoopService.ZEBRA_SERVER_LOOP])
            .to(classOf[SimpleSelectLoop])
            .asEagerSingleton()
        bind(classOf[SelectLoopService]).asEagerSingleton()
    }

    protected def peerResolver(hostId: UUID, vt: VirtualTopology): PeerResolver =
        new PeerResolver(hostId, injector.getInstance(classOf[MidonetBackend]), vt)

    protected def virtualTopology(simBackChannel: SimulationBackChannel) = {
        val vtThread = new AtomicLong(-1)
        val vtExecutor = Executors.singleThreadScheduledExecutor(
            "devices-service", isDaemon = true, Executors.CallerRunsPolicy)
        val ioExecutor = Executors.cachedPoolExecutor(
            "devices-io", isDaemon = true, Executors.CallerRunsPolicy)
        val vtExecutorCheck = () => {
            if (vtThread.get < 0) {
                vtThread.compareAndSet(-1, Thread.currentThread().getId)
                true
            } else {
                vtThread.get == Thread.currentThread().getId
            }
        }

        new VirtualTopology(
            injector.getInstance(classOf[MidonetBackend]),
            config,
            simBackChannel,
            ruleLogEventChannel(1 << 12), // TODO: Make capacity configurable
            metricRegistry,
            vtExecutor,
            ioExecutor,
            vtExecutorCheck
            )
    }

    protected def virtualToPhysicalMapper(hostId: UUID, vt: VirtualTopology) =
        new VirtualToPhysicalMapper(
            injector.getInstance(classOf[MidonetBackend]),
            vt,
            reflections,
            hostId)

    protected def crashStrategy(): SupervisorStrategy =
        new OneForOneStrategy()({ case t =>
            log.warn("Actor crashed, aborting", t)
            Midolman.exitAsync(-1)
            akka.actor.SupervisorStrategy.stop
        })
}
