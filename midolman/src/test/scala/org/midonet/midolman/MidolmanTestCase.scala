/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.compat.Platform
import scala.annotation.tailrec
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import akka.actor._
import akka.dispatch.{Await, Future}
import akka.testkit._
import akka.util.{Duration, Timeout}
import akka.util.duration._

import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest._
import org.scalatest.matchers.{BePropertyMatcher, BePropertyMatchResult,
        ShouldMatchers}
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.guice._
import org.midonet.midolman.guice.actors.{OutgoingMessage,
                                          TestableMidolmanActorsModule}
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.MockConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.reactor.ReactorModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.layer4.NatMappingFactory
import org.midonet.midolman.monitoring.{MonitoringActor, MonitoringAgent}
import org.midonet.midolman.services.{HostIdProviderService,
        MidolmanActorsService, MidolmanService}
import org.midonet.midolman.topology.{LocalPortActive, VirtualTopologyActor,
        VirtualToPhysicalMapper}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.cluster.services.MidostoreSetupService
import org.midonet.odp._
import org.midonet.odp.flows.FlowKeyInPort
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.protos.mocks.MockOvsDatapathConnectionImpl
import org.midonet.packets.Ethernet
import org.midonet.util.functors.callbacks.AbstractCallback
import protos.mocks.MockOvsDatapathConnectionImpl.FlowListener
import org.midonet.cluster.data.{Port => VPort}
import org.midonet.cluster.data.host.Host
import org.midonet.midolman.version.guice.VersionModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.guice.actors.OutgoingMessage
import org.midonet.midolman.DatapathController.InitializationComplete
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.DeduplicationActor.DiscardPacket


object MidolmanTestCaseLock {
    val sequential: ReentrantLock = new ReentrantLock()
}

trait MidolmanTestCase extends Suite with BeforeAndAfter
        with OneInstancePerTest with ShouldMatchers with Dilation {

    case class PacketsExecute(packet: Packet)
    case class FlowAdded(flow: Flow)
    case class FlowRemoved(flow: Flow)

    private val log: Logger = LoggerFactory.getLogger(classOf[MidolmanTestCase])

    var injector: Injector = null
    var mAgent: MonitoringAgent = null
    var interfaceScanner: MockInterfaceScanner = null
    var sProbe: TestProbe = null

    var packetInProbe: TestProbe = null
    var wflowAddedProbe: TestProbe = null
    var wflowRemovedProbe: TestProbe = null
    var portsProbe: TestProbe = null
    var discardPacketProbe: TestProbe = null

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    def mockDpConn(): MockOvsDatapathConnectionImpl = {
        dpConn().asInstanceOf[MockOvsDatapathConnectionImpl]
    }

    protected def dpConn(): OvsDatapathConnection = {
        injector.getInstance(classOf[OvsDatapathConnection])
    }

    protected def actors(): ActorSystem = {
        injector.getInstance(classOf[MidolmanActorsService]).system
    }

    protected def clusterClient(): Client = {
        injector.getInstance(classOf[Client])
    }

    protected def clusterDataClient(): DataClient = {
        injector.getInstance(classOf[DataClient])
    }

    protected def natMappingFactory(): NatMappingFactory = {
        injector.getInstance(classOf[NatMappingFactory])
    }

    protected def newProbe(): TestProbe = {
        new TestProbe(actors())
    }

    protected def hostId(): UUID = {
        injector.getInstance(classOf[HostIdProviderService]).getHostId
    }

    protected def makeEventProbe[T](klass: Class[T]): TestProbe = {
        val probe = newProbe()
        actors().eventStream.subscribe(probe.ref, klass)
        probe
    }

    before {
        try {
            val config = fillConfig(new HierarchicalConfiguration())
            injector = Guice.createInjector(getModulesAsJavaIterable(config))

            injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
            injector.getInstance(classOf[MidolmanService]).startAndWait()
            mAgent = injector.getInstance(classOf[MonitoringAgent])
            mAgent.startMonitoringIfEnabled()
            interfaceScanner = injector.getInstance(classOf[InterfaceScanner])
                .asInstanceOf[MockInterfaceScanner]

            mockDpConn().packetsExecuteSubscribe(
                new AbstractCallback[Packet, Exception] {
                    override def onSuccess(pkt: Packet) {
                        actors().eventStream.publish(PacketsExecute(pkt))
                    }
                })

            mockDpConn().flowsSubscribe(
                new FlowListener {
                    def flowCreated(flow: Flow) {
                        actors().eventStream.publish(FlowAdded(flow))
                    }

                    def flowDeleted(flow: Flow) {
                        actors().eventStream.publish(FlowRemoved(flow))
                    }
                })

            sProbe = newProbe()
            actors().eventStream.subscribe(sProbe.ref, classOf[PacketIn])
            actors().eventStream.subscribe(sProbe.ref,
                classOf[EmitGeneratedPacket])

            // Make sure that each test method runs alone
            MidolmanTestCaseLock.sequential.lock()
            log.info("Acquired test lock")
            actors().settings.DebugEventStream
            packetInProbe = makeEventProbe(classOf[PacketIn])
            wflowAddedProbe = makeEventProbe(classOf[WildcardFlowAdded])
            wflowRemovedProbe = makeEventProbe(classOf[WildcardFlowRemoved])
            portsProbe = makeEventProbe(classOf[LocalPortActive])
            discardPacketProbe = makeEventProbe(classOf[DiscardPacket])

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {

        injector.getInstance(classOf[MidolmanService]).stopAndWait()
        if (mAgent != null) {
            mAgent.stop()
        }

        MidolmanTestCaseLock.sequential.unlock()
        log.info("Released lock")

        afterTest()
    }

    // These methods can be overridden by each class mixing MidolmanTestCase
    // to add custom operations before each test and after each tests
    protected def beforeTest() {}

    protected def afterTest() {}

    val probesByName = mutable.Map[String, TestKit]()
    val actorsByName = mutable.Map[String, TestActorRef[Actor]]()

    protected def getModulesAsJavaIterable(config: HierarchicalConfiguration)
            : java.lang.Iterable[Module] = {
        asJavaIterable(getModules(config))
    }

    protected def getModules(config: HierarchicalConfiguration)
            : List[Module] = {
        List[Module](
            new VersionModule(),
            new SerializationModule(),
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockFlowStateCacheModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                        .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new ReactorModule(),
            new MockMonitoringStoreModule(),
            new ClusterClientModule(),
            new TestableMidolmanActorsModule(probesByName, actorsByName),
            new MidolmanModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                        .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            },
            getConditionSetModule
        )
    }

    protected def getConditionSetModule = new DummyConditionSetModule(false)

    protected def ask[T](actor: ActorRef, msg: Object): T = {
        val t = Timeout(3 second)
        val promise = akka.pattern.ask(actor, msg)(t).asInstanceOf[Future[T]]

        Await.result(promise, t.duration)
    }

    def materializePort(port: VPort[_, _], host: Host, name: String): Unit = {
        clusterDataClient().hostsAddVrnPortMapping(host.getId, port.getId, name)
        if (host.getId == hostId()) {
            val itf = new InterfaceDescription(name)
            itf.setHasLink(true)
            itf.setUp(true)
            interfaceScanner.addInterface(itf)
        }
    }

    def datapathPorts(datapath: Datapath): mutable.Map[String, Port[_, _]] = {

        val ports: mutable.Set[Port[_, _]] =
            dpConn().portsEnumerate(datapath).get()

        val portsByName = mutable.Map[String, Port[_, _]]()
        for (port <- ports) {
            portsByName.put(port.getName, port)
        }

        portsByName
    }

    protected def probeByName(name: String): TestKit = {
        probesByName(name)
    }

    protected def actorByName[A <: Actor](name: String): TestActorRef[A] = {
        actorsByName(name).asInstanceOf[TestActorRef[A]]
    }

    protected def initializeDatapath(): InitializationComplete = {

        val result = ask[InitializationComplete](
            DatapathController.getRef(actors()), Initialize())

        dpProbe().expectMsgType[Initialize] should not be null
        dpProbe().expectMsgType[OutgoingMessage] should not be null

        result
    }

    protected def getPortNumber(portName: String): Int = {
        dpController().underlyingActor.ifaceNameToDpPort(portName).getPortNo
    }

    protected def getPort(portName: String) = {
        dpController().underlyingActor.ifaceNameToDpPort(portName)
    }

    protected def triggerPacketIn(portName: String, ethPkt: Ethernet) {
        val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
            .addKey(new FlowKeyInPort().setInPort(getPortNumber(portName)))
        val dpPkt = new Packet()
            .setMatch(flowMatch)
            .setPacket(ethPkt)
        triggerPacketIn(dpPkt)
    }

    protected def triggerPacketIn(packet: Packet) {
        dpConn().asInstanceOf[MockOvsDatapathConnectionImpl].triggerPacketIn(packet)
    }

    protected def setFlowLastUsedTimeToNow(flow: FlowMatch) {
        dpConn().asInstanceOf[MockOvsDatapathConnectionImpl].setFlowLastUsedTimeToNow(flow)
    }

    protected def dpController(): TestActorRef[DatapathController] = {
        actorByName(DatapathController.Name)
    }

    protected def flowController(): TestActorRef[FlowController] = {
        actorByName(FlowController.Name)
    }

    protected def monitoringController(): TestActorRef[MonitoringActor] = {
        actorByName(MonitoringActor.Name)
    }

    protected def virtualToPhysicalMapper(): TestActorRef[VirtualToPhysicalMapper] = {
        actorByName(VirtualToPhysicalMapper.Name)
    }

    protected def virtualTopologyActor(): TestActorRef[VirtualTopologyActor] = {
        actorByName(VirtualTopologyActor.Name)
    }

    protected def dpProbe(): TestKit = {
        probeByName(DatapathController.Name)
    }

    protected def flowProbe(): TestKit = {
        probeByName(FlowController.Name)
    }

    protected def dedupProbe(): TestKit = {
        probeByName(DeduplicationActor.Name)
    }

    protected def vtpProbe(): TestKit = {
        probeByName(VirtualToPhysicalMapper.Name)
    }

    protected def vtaProbe(): TestKit = {
        probeByName(VirtualTopologyActor.Name)
    }

    // TODO(pino): clean-up. Leave it for now to minimize test-code changes.
    protected def simProbe() = sProbe

    protected def fishForRequestOfType[T](testKit: TestKit,
            timeout: Duration = Duration(3, TimeUnit.SECONDS))
            (implicit m: Manifest[T]):T = {

        def messageMatcher(clazz: Class[_]): PartialFunction[Any, Boolean] = {
            {
                case o if (o.getClass == clazz) => true
                case _ => false
            }
        }
        val clazz = m.erasure.asInstanceOf[Class[T]]
        val msg = testKit.fishForMessage(timeout)(messageMatcher(clazz))
        assert(clazz.isInstance(msg),
               "Message should have been of type %s but was %s" format
                   (clazz, msg.getClass))
        clazz.cast(msg)
    }

    protected def fishForReplyOfType[T](testKit: TestKit,
                                        timeout: Duration = Duration(3, TimeUnit.SECONDS))
                                       (implicit m: Manifest[T]): T = {
        val deadline = Platform.currentTime + timeout.toMillis
        val clazz = manifest.erasure.asInstanceOf[Class[T]]
        @tailrec
        def fish: T = {
            val timeLeft = deadline - Platform.currentTime
            assert(timeLeft > 0,
                   "timeout waiting for reply of type %s" format clazz)
            val outMsg = fishForRequestOfType[OutgoingMessage](
                testKit, Duration(timeLeft, TimeUnit.MILLISECONDS))
            assert(outMsg != null,
                   "timeout waiting for reply of type %s" format clazz)
            if (!clazz.isInstance(outMsg.m))
                fish
            else
                clazz.cast(outMsg.m)
        }
        fish
    }

    protected def requestOfType[T](testKit: TestKit)
                                  (implicit m: Manifest[T]): T = {
        testKit.expectMsgClass(m.erasure.asInstanceOf[Class[T]])
    }

    protected def replyOfType[T](testKit: TestKit)
                                (implicit manifest: Manifest[T]): T = {
        val clazz = manifest.erasure.asInstanceOf[Class[T]]
        val m = testKit.expectMsgClass(classOf[OutgoingMessage]).m
        assert(clazz.isInstance(m),
               "Reply should have been of type %s but was %s" format
                   (clazz, m.getClass))
        clazz.cast(m)
    }

    protected def as[T](o: AnyRef)(implicit m: Manifest[T]): T = {
        o should be (anInstanceOf[T])
        o.asInstanceOf[T]
    }

    def anInstanceOf[T](implicit manifest: Manifest[T]) = {
        val clazz = manifest.erasure.asInstanceOf[Class[T]]
        new BePropertyMatcher[AnyRef] {
            def apply(left: AnyRef) =
                BePropertyMatchResult(clazz.isAssignableFrom(left.getClass),
                                      "an instance of " + clazz.getName)
        }
    }

    protected def drainProbe(testKit: TestKit) {
        while (testKit.msgAvailable)
            testKit.receiveOne(testKit.remaining)
    }

    protected def drainProbes() {
        drainProbe(vtaProbe())
        drainProbe(simProbe())
        drainProbe(flowProbe())
        drainProbe(vtpProbe())
        drainProbe(dpProbe())
        drainProbe(dedupProbe())
        drainProbe(discardPacketProbe)
        drainProbe(wflowAddedProbe)
        drainProbe(wflowRemovedProbe)
        drainProbe(packetInProbe)
    }
}

trait Dilation {

    protected def actors(): ActorSystem

    protected def getDilationTime = {
        TestKitExtension(actors()).TestTimeFactor.toLong
    }

    def dilatedSleep(sleepTime: Long) {
        Thread.sleep(getDilationTime * sleepTime)
    }

    def getDilatedTime(time: Long): Long = {
        getDilationTime * time
    }

}
