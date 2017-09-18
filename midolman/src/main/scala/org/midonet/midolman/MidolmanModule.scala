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
import com.google.common.collect.Lists
import com.google.inject.AbstractModule
import com.lmax.disruptor._
import com.typesafe.config.ConfigFactory

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.Util
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{FlowStateStorage, MidonetBackendConfig}
import org.midonet.conf.HostIdGenerator
import org.midonet.insights.Insights
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath._
import org.midonet.midolman.host.scanner.{DefaultInterfaceScanner, InterfaceScanner}
import org.midonet.midolman.host.services.{HostService, QosService, TcRequestHandler}
import org.midonet.midolman.io._
import org.midonet.midolman.logging.rule.{DisruptorRuleLogEventChannel, RuleLogEventChannel}
import org.midonet.midolman.logging.{FlowTracingAppender, FlowTracingSchema}
import org.midonet.midolman.management.{MeteringHTTPHandler, SimpleHTTPServerService}
import org.midonet.midolman.monitoring.metrics.{DatapathMetrics, PacketExecutorMetrics}
import org.midonet.midolman.openstack.metadata.{DatapathInterface, Plumber}
import org.midonet.midolman.services._
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.vpp.{VppController, VppOvs}
import org.midonet.netlink.{NetlinkChannelFactory, NetlinkProtocol, NetlinkUtil}
import org.midonet.odp.{Datapath, OvsNetlinkFamilies}
import org.midonet.util._
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.{Reactor, SelectLoop, SimpleSelectLoop}

class MidolmanModule(config: MidolmanConfig,
                     backend: MidonetBackend,
                     backendConfig: MidonetBackendConfig,
                     directoryReactor: Reactor,
                     metricRegistry: MetricRegistry,
                     flowTablePreallocation: FlowTablePreallocation) extends AbstractModule {
    private val log: Logger = LoggerFactory.getLogger(classOf[MidolmanModule])

    val cbRegistry = new CallbackRegistryImpl

    override def configure(): Unit = {
        val init0 = System.nanoTime()
        bind(classOf[MidolmanConfig]).toInstance(config)
        val init1 = Midolman.mark("MIDOLMAN MODULE -> bind config", init0)
        val host = hostId()
        val init2 = Midolman.mark("MIDOLMAN MODULE -> hostid", init1)
        val hostIdProvider = new HostIdProvider {
            override def hostId(): UUID = host
        }
        bind(classOf[HostIdProvider]).toInstance(hostIdProvider)

        bind(classOf[NanoClock]).toInstance(NanoClock.DEFAULT)
        bind(classOf[UnixClock]).toInstance(UnixClock.DEFAULT)

        bind(classOf[MetricRegistry]).toInstance(metricRegistry)
        bind(classOf[CallbackRegistry]).toInstance(cbRegistry)

        val init3 = Midolman.mark("MIDOLMAN MODULE -> some bindings", init2)
        val insights = new Insights(config.insights, metricRegistry)
        bind(classOf[Insights]).toInstance(insights)
        val init4 = Midolman.mark("MIDOLMAN MODULE -> bind insights", init3)

        // We add an extra slot so that channels can return tokens
        // they obtained due to the multiplier effect but didn't use.
        val counter = new StatisticalCounter(config.simulationThreads + 1)
        val policy = htbPolicy(counter)
        val init5 = Midolman.mark("MIDOLMAN MODULE -> htb policy", init4)
        bind(classOf[StatisticalCounter]).toInstance(counter)
        bind(classOf[TokenBucketPolicy]).toInstance(policy)

        val channelFactory = netlinkChannelFactory()
        val init6 = Midolman.mark("MIDOLMAN MODULE -> netlink channel factory", init5)
        val families = ovsNetlinkFamilies(channelFactory)
        val init7 = Midolman.mark("MIDOLMAN MODULE -> ovs netlink families", init6)
        val dp = datapath(channelFactory, families, metricRegistry)
        val init8 = Midolman.mark("MIDOLMAN MODULE -> datapath", init7)
        if (config.reclaimDatapath) {
            val init9 = System.nanoTime()
            val flowList = reclaimFlows(dp, config, metricRegistry)
            val init10 = Midolman.mark("MIDOLMAN MODULE -> reclaimFlows", init9)
            val flowExpirator = new FlowExpirator(flowList, config, dp,
                families, channelFactory)
            bind(classOf[FlowExpirator]).toInstance(flowExpirator)
            val init11 = Midolman.mark("MIDOLMAN MODULE -> flow expirator instantiation", init10)
        }
        val init12 = System.nanoTime()
        val dpState = datapathStateDriver(dp)
        val init13 = Midolman.mark("MIDOLMAN MODULE -> datapath state driver", init12)
        bind(classOf[NetlinkChannelFactory]).toInstance(channelFactory)
        bind(classOf[OvsNetlinkFamilies]).toInstance(families)
        bind(classOf[DatapathStateDriver]).toInstance(dpState)
        bind(classOf[DatapathState]).to(classOf[DatapathStateDriver])
        bind(classOf[VirtualPortsResolver]).to(classOf[DatapathStateDriver])
        bind(classOf[UnderlayResolver]).to(classOf[DatapathStateDriver])

        val init14 = Midolman.mark("MIDOLMAN MODULE -> some more bindings", init13)
        val as = actorSystem()
        val init15 = Midolman.mark("MIDOLMAN MODULE -> actor system", init14)

        bind(classOf[ActorSystem]).toInstance(as)
        bind(classOf[SupervisorStrategy]).toInstance(crashStrategy())

        val backChannel = new ShardedSimulationBackChannel
        bind(classOf[ShardedSimulationBackChannel]).toInstance(backChannel)
        bind(classOf[SimulationBackChannel]).toInstance(backChannel)

        val capacity = Util.findNextPositivePowerOfTwo(
            config.datapath.globalIncomingBurstCapacity)
        val ringBuffer = RingBuffer
            .createMultiProducer(DisruptorDatapathChannel.Factory, capacity)
        val init16 = Midolman.mark("MIDOLMAN MODULE -> ring buffer", init15)
        val barrier = ringBuffer.newBarrier()
        val fp = flowProcessor(dpState, families, channelFactory, backChannel)
        val init17 = Midolman.mark("MIDOLMAN MODULE -> flow processor", init16)
        val channel = datapathChannel(
            ringBuffer, barrier, fp, dpState, families, channelFactory)
        val init18 = Midolman.mark("MIDOLMAN MODULE -> datapath channel", init17)
        bind(classOf[FlowProcessor]).toInstance(fp)
        bind(classOf[DatapathChannel]).toInstance(channel)

        bind(classOf[DatapathConnectionPool]).toInstance(connectionPool())
        val init19 = Midolman.mark("MIDOLMAN MODULE -> connection pool", init18)

        bind(classOf[DatapathService]).asEagerSingleton()

        bind(classOf[FlowStateStorageFactory]).toInstance(flowStateStorageFactory())
        val init20 = Midolman.mark("MIDOLMAN MODULE -> flow state storage factory", init19)

        bindActorService()
        val init21 = Midolman.mark("MIDOLMAN MODULE -> bind actor service", init20)

        val scanner = interfaceScanner(channelFactory)
        bind(classOf[InterfaceScanner]).toInstance(scanner)
        val init22 = Midolman.mark("MIDOLMAN MODULE -> interface scanner", init21)

        val hservice = hostService(host, scanner)
        bind(classOf[HostService]).toInstance(hservice)
        val init23 = Midolman.mark("MIDOLMAN MODULE -> host service", init22)

        bind(classOf[Plumber]).toInstance(plumber(dpState))
        val init24 = Midolman.mark("MIDOLMAN MODULE -> plumber", init23)

        val tcRequestHandler = TcRequestHandler(channelFactory)
        bind(classOf[TcRequestHandler]).toInstance(tcRequestHandler)
        val init25 = Midolman.mark("MIDOLMAN MODULE -> tc request handler", init24)

        val qService = qosService(scanner, host, tcRequestHandler)
        bind(classOf[QosService]).toInstance(qService)
        val init26 = Midolman.mark("MIDOLMAN MODULE -> qos service", init25)

        val statsHttpSvc = statsHttpService()
        bind(classOf[SimpleHTTPServerService]).toInstance(statsHttpSvc)
        val init27 = Midolman.mark("MIDOLMAN MODULE -> http service", init26)

        bind(classOf[FlowTracingAppender]).toInstance(flowTracingAppender())
        val init28 = Midolman.mark("MIDOLMAN MODULE -> flow tracing appender", init27)

        val allocator = natAllocator()
        bind(classOf[NatBlockAllocator]).toInstance(allocator)
        val init29 = Midolman.mark("MIDOLMAN MODULE -> nat allocator", init28)
        bindSelectLoopService()
        val init30 = Midolman.mark("MIDOLMAN MODULE -> bind select loop service", init29)

        val vt = virtualTopology(backChannel)
        bind(classOf[VirtualTopology]).toInstance(vt)
        val init31 = Midolman.mark("MIDOLMAN MODULE -> virtual topology", init30)

        val vtpm = virtualToPhysicalMapper(host, vt)
        bind(classOf[VirtualToPhysicalMapper]).toInstance(vtpm)
        val init32 = Midolman.mark("MIDOLMAN MODULE -> virtual to physical mapper", init31)

        val resolver = peerResolver(host, vt)
        bind(classOf[PeerResolver]).toInstance(resolver)
        val init33 = Midolman.mark("MIDOLMAN MODULE -> peer resolver", init32)

        val workersService = createPacketWorkersService(config, hostIdProvider,
                                                        channel, dpState, fp,
                                                        allocator, resolver,
                                                        backChannel, vt,
                                                        NanoClock.DEFAULT,
                                                        backend,
                                                        metricRegistry,
                                                        insights,
                                                        counter, as,
                                                        flowTablePreallocation)
        bind(classOf[PacketWorkersService]).toInstance(workersService)
        val init34 = Midolman.mark("MIDOLMAN MODULE -> worker service", init33)

        val dpConnectionManager = upcallDatapathConnectionManager(
            policy, workersService.workers)
        bind(classOf[UpcallDatapathConnectionManager]).toInstance(
            dpConnectionManager)
        val init35 = Midolman.mark("MIDOLMAN MODULE -> upcall datapath manager", init34)
        bind(classOf[DatapathInterface]).toInstance(
            datapathInterface(scanner, dpState, dpConnectionManager))
        val init36 = Midolman.mark("MIDOLMAN MODULE -> datapath interface", init35)

        bind(classOf[VppController]).toInstance(vppController(host, dpState, vt))
        val init37 = Midolman.mark("MIDOLMAN MODULE -> vpp controller", init36)

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

    protected def datapath(channelFactory: NetlinkChannelFactory,
                           families: OvsNetlinkFamilies,
                           metrics: MetricRegistry) =
        DatapathBootstrap.bootstrap(config, channelFactory, families)

    protected def reclaimFlows(dp: Datapath,
                               config: MidolmanConfig,
                               metric: MetricRegistry) =
        DatapathBootstrap.reclaimFlows(dp, config, metric)

    protected def datapathStateDriver(dp: Datapath) =
        new DatapathStateDriver(dp)

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
        if (threads == 1) {
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
                                             insights: Insights,
                                             counter: StatisticalCounter,
                                             actorSystem: ActorSystem,
                                             flowTablePreallocation: FlowTablePreallocation)
            : PacketWorkersService =
        new PacketWorkersServiceImpl(config, hostIdProvider, dpChannel, dpState,
                                     flowProcessor, natBlockAllocator, peerResolver,
                                     backChannel, vt, clock, backend,
                                     metricsRegistry, insights, counter, actorSystem,
                                     flowTablePreallocation, cbRegistry)

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
            backendConfig,
            backend,
            interfaceScanner,
            hostId,
            directoryReactor)

    protected def qosService(scanner: InterfaceScanner,
                             hostId: UUID,
                             tcRequestHandler: TcRequestHandler): QosService =
        QosService(scanner, hostId, tcRequestHandler)

    protected def statsHttpService(): SimpleHTTPServerService = {
        new SimpleHTTPServerService(
            config.statsHttpServerPort,
                Lists.newArrayList(new MeteringHTTPHandler))
    }

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
        new PeerResolver(hostId, backend, vt)

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
            backend,
            config,
            simBackChannel,
            ruleLogEventChannel(1 << 12), // TODO: Make capacity configurable
            metricRegistry,
            vtExecutor,
            ioExecutor,
            vtExecutorCheck,
            cbRegistry)
    }

    protected def virtualToPhysicalMapper(hostId: UUID, vt: VirtualTopology) =
        new VirtualToPhysicalMapper(
            backend,
            vt,
            hostId)

    protected def vppController(hostId: UUID,
                                datapathState: DatapathState,
                                vt: VirtualTopology): VppController =
        new VppController(hostId, datapathState,
                          new VppOvs(datapathState.datapath, config.fip64), vt)

    protected def crashStrategy(): SupervisorStrategy =
        new OneForOneStrategy()({ case t =>
            log.warn("Actor crashed, aborting", t)
            Midolman.exitAsync(-1)
            akka.actor.SupervisorStrategy.stop
        })
}
