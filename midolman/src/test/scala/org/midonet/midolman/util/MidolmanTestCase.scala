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

package org.midonet.midolman.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.{List => JList, UUID}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.implicitConversions

import akka.actor._
import akka.event.EventStream
import akka.pattern.ask
import akka.testkit._
import akka.util.Timeout
import com.google.inject._
import com.typesafe.config.{ConfigFactory, Config}
import com.typesafe.scalalogging.Logger
import org.scalatest._
import org.scalatest.matchers.{BePropertyMatchResult, BePropertyMatcher}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.{Port => VPort}
import org.midonet.cluster.services.{LegacyStorageService, MidonetBackend}
import org.midonet.cluster.storage.{StateStorageModule, MidonetBackendTestModule}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.DatapathController.{DatapathReady, Initialize}
import org.midonet.midolman.DeduplicationActor.{DiscardPacket, HandlePackets}
import org.midonet.midolman.FlowController.{FlowUpdateCompleted, WildcardFlowAdded, WildcardFlowRemoved}
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman._
import org.midonet.midolman.cluster._
import org.midonet.midolman.cluster.datapath.MockDatapathModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.state.MockFlowStateStorageModule
import org.midonet.midolman.cluster.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice.config.MidolmanConfigModule
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{HostIdProviderService, MidolmanActorsService, MidolmanService}
import org.midonet.midolman.topology.{LocalPortActive, VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.util.guice.{MockMidolmanModule, OutgoingMessage, TestableMidolmanActorsModule}
import org.midonet.midolman.util.mock.MockInterfaceScanner
import org.midonet.odp._
import org.midonet.odp.flows.FlowKeys.inPort
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowActionSetKey, FlowKeyTunnel}
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.packets.Ethernet
import org.midonet.util.concurrent.MockClock

object MidolmanTestCaseLock {
    val sequential: ReentrantLock = new ReentrantLock()
}

trait MidolmanTestCase extends Suite with BeforeAndAfter
                                     with OneInstancePerTest
                                     with Matchers
                                     with MidolmanServices
                                     with VirtualConfigurationBuilders { self =>

    case class PacketsExecute(packet: Packet, actions: JList[FlowAction])
    case class FlowAdded(flow: Flow)
    case class FlowRemoved(flow: Flow)

    private val log = Logger(LoggerFactory.getLogger(classOf[MidolmanTestCase]))

    var injector: Injector = null
    var interfaceScanner: MockInterfaceScanner = null

    // actor probes

    var datapathEventsProbe: TestProbe = null
    var sProbe: TestProbe = null
    var packetInProbe: TestProbe = null
    var packetsEventsProbe: TestProbe = null
    var wflowAddedProbe: TestProbe = null
    var wflowAddReqProbe: TestProbe = null
    var wflowRemovedProbe: TestProbe = null
    var portsProbe: TestProbe = null
    var discardPacketProbe: TestProbe = null
    var flowUpdateProbe: TestProbe = null

    val clock = new MockClock

    implicit val askTimeout = Timeout(3 seconds)
    val timeout: FiniteDuration = askTimeout.duration

    protected def fillConfig(config: Config) : Config = {
        val defaults =
            """
              |midolman.root_key = "/test/v3/midolman"
              |cassandra.servers = "localhost:9171"
            """.stripMargin

        ConfigFactory.parseString(defaults).withFallback(config)
    }

    protected def actors: ActorSystem =
        injector.getInstance(classOf[MidolmanActorsService]).system

    implicit protected def system: ActorSystem = actors
    implicit protected def executor: ExecutionContext = actors.dispatcher

    protected def newProbe(): TestProbe = {
        new TestProbe(actors)
    }

    private def registerProbe[T](p: TestProbe, k: Class[T], s: EventStream) =
        s.subscribe(p.ref, k)

    protected def makeEventProbe[T](klass: Class[T]): TestProbe = {
        val probe = new TestProbe(actors)
        registerProbe(probe, klass, actors.eventStream)
        probe
    }

    private def prepareAllProbes() {
        sProbe = new TestProbe(actors)
        registerProbe(sProbe, classOf[PacketIn], actors.eventStream)
        datapathEventsProbe = makeEventProbe(classOf[DatapathReady])
        packetInProbe = makeEventProbe(classOf[PacketIn])
        packetsEventsProbe = makeEventProbe(classOf[PacketsExecute])
        wflowAddedProbe = makeEventProbe(classOf[WildcardFlowAdded])
        wflowRemovedProbe = makeEventProbe(classOf[WildcardFlowRemoved])
        portsProbe = makeEventProbe(classOf[LocalPortActive])
        discardPacketProbe = makeEventProbe(classOf[DiscardPacket])
        flowUpdateProbe = makeEventProbe(classOf[FlowUpdateCompleted])
    }

    before {
        try {
            injector = Guice.createInjector(getModulesAsJavaIterable(fillConfig(ConfigFactory.empty)))

            injector.getInstance(classOf[LegacyStorageService])
                    .startAsync()
                    .awaitRunning()
            injector.getInstance(classOf[MidonetBackend])
                    .startAsync()
                    .awaitRunning()
            injector.getInstance(classOf[MidolmanService])
                    .startAsync()
                    .awaitRunning()
            interfaceScanner = injector.getInstance(classOf[InterfaceScanner])
                .asInstanceOf[MockInterfaceScanner]

            mockDpChannel.packetsExecuteSubscribe {
                (pkt: Packet, actions: JList[FlowAction]) =>
                    actors.eventStream.publish(PacketsExecute(pkt, actions))
            }

            mockDpChannel.flowCreateSubscribe { flow =>
                actors.eventStream.publish(FlowAdded(flow))
            }

            mockFlowEjector.flowDeleteSubscribe { flow =>
                actors.eventStream.publish(FlowRemoved(flow))
            }

            // Make sure that each test method runs alone
            MidolmanTestCaseLock.sequential.lock()
            log.info("Acquired test lock")
            actors.settings.DebugEventStream

            prepareAllProbes()

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        injector.getInstance(classOf[LegacyStorageService])
                .stopAsync()
                .awaitTerminated()
        injector.getInstance(classOf[MidonetBackend])
                .stopAsync()
                .awaitTerminated()
        injector.getInstance(classOf[MidolmanService])
                .stopAsync()
                .awaitTerminated()
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

    protected def getModulesAsJavaIterable(config: Config)
            : java.lang.Iterable[Module] = {
        asJavaIterable(getModules(config))
    }

    protected def getModules(config: Config)
            : List[Module] = {
        List[Module](
            new SerializationModule(),
            new MidonetBackendTestModule(),
            new StateStorageModule(),
            new MidolmanConfigModule(MidoTestConfigurator.forAgents(config)),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
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
            new LegacyClusterModule(),
            new MockMidolmanModule(),
            new TestableMidolmanActorsModule(probesByName, actorsByName, clock),
            new ResourceProtectionModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                        .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            }
        )
    }

    protected def askAndAwait[T](actor: ActorRef, msg: Object): T = {
        val promise = ask(actor, msg).asInstanceOf[Future[T]]
        Await.result(promise, timeout)
    }

    def materializePort(port: VPort[_, _],
                        host: Host, name: String): VPort[_,_] = {
        val updatedPort =
            clusterDataClient
                .hostsAddVrnPortMappingAndReturnPort(host.getId, port.getId, name)

        stateStorage.setPortLocalAndActive(port.getId, host.getId, true)

        if (host.getId == hostId) {
            val itf = new InterfaceDescription(name)
            itf.setHasLink(true)
            itf.setUp(true)
            interfaceScanner.addInterface(itf)
        }
        updatedPort
    }

    def datapathPorts(datapath: Datapath): mutable.Map[String, DpPort] = {

        val ports: mutable.Set[DpPort] =
            dpConn().futures.portsEnumerate(datapath).get()

        val portsByName = mutable.Map[String, DpPort]()
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

    protected def initializeDatapath(): String = {
        val result = Await.result(dpController() ? Initialize, timeout)
        result shouldBe a [DatapathReady]
        "ok"
    }

    protected def triggerPacketIn(portName: String, ethPkt: Ethernet) {
        val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
                                   .addKey(inPort(getPortNumber(portName)))
        triggerPacketIn(new Packet(ethPkt, flowMatch))
    }

    protected def triggerPacketsIn(portName: String, ethPkts: List[Ethernet]) {
        val pkts = ethPkts map { ethPkt =>
            val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
                                       .addKey(inPort(getPortNumber(portName)))
            new Packet(ethPkt, flowMatch)
        }
        triggerPacketsIn(pkts)
    }

    protected def triggerPacketIn(packet: Packet) {
        deduplicationActor() ! HandlePackets(Array(packet))
    }

    protected def triggerPacketsIn(packets: List[Packet]) {
        dpConn().asInstanceOf[MockOvsDatapathConnection]
                .triggerPacketsIn(packets)
    }

    protected def setFlowLastUsedTimeToNow(flow: FlowMatch) {
        dpConn().asInstanceOf[MockOvsDatapathConnection].setFlowLastUsedTimeToNow(flow)
    }

    protected def dpController(): TestActorRef[DatapathController] = {
        actorByName(DatapathController.Name)
    }

    def dpState: DatapathState = dpController().underlyingActor.dpState

    def getPort(portName: String) =
        dpState.getDpPortForInterface(portName).orNull

    def getPortNumber(portName: String): Int = getPort(portName).getPortNo

    def vifToLocalPortNumber(vportId: UUID): Option[Short] =
        dpState.getDpPortNumberForVport(vportId) map { _.shortValue }

    protected def flowController(): TestActorRef[FlowController] = {
        actorByName(FlowController.Name)
    }

    protected def virtualToPhysicalMapper()
    :TestActorRef[VirtualToPhysicalMapper] = {
        actorByName(VirtualToPhysicalMapper.Name)
    }

    protected def virtualTopologyActor(): TestActorRef[VirtualTopologyActor] = {
        actorByName(VirtualTopologyActor.Name)
    }

    protected def deduplicationActor(): TestActorRef[PacketsEntryPoint] = {
        actorByName(PacketsEntryPoint.Name)
    }

    protected def dpProbe(): TestKit = {
        probeByName(DatapathController.Name)
    }

    protected def flowProbe(): TestKit = {
        probeByName(FlowController.Name)
    }

    protected def dedupProbe(): TestKit = {
        probeByName(PacketsEntryPoint.Name)
    }

    protected def vtpProbe(): TestKit = {
        probeByName(VirtualToPhysicalMapper.Name)
    }

    protected def vtaProbe(): TestKit = {
        probeByName(VirtualTopologyActor.Name)
    }

    protected def fishForRequestOfType[T](
            testKit: TestKit, _timeout: Duration = timeout)(
            implicit m: Manifest[T]): T = {

        val clazz = m.runtimeClass.asInstanceOf[Class[T]]
        val msg = testKit.fishForMessage(_timeout) {
            case o if o.getClass == clazz => true
            case _ => false
        }
        assert(clazz.isInstance(msg),
               "Message should have been of type %s but was %s" format
                   (clazz, msg.getClass))
        clazz.cast(msg)
    }

    protected def fishForReplyOfType[T](
            testKit: TestKit, _timeout: Duration = timeout)(
            implicit m: Manifest[T]): T = {
        val deadline = Platform.currentTime + _timeout.toMillis
        val clazz = manifest.runtimeClass.asInstanceOf[Class[T]]
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
        testKit.expectMsgClass(m.runtimeClass.asInstanceOf[Class[T]])
    }

    trait OnProbe[T] { def on(probe: TestKit): T }

    def expect[T](implicit m: Manifest[T]): OnProbe[T] =
        new OnProbe[T] {
            override def on(probe: TestKit): T =
                probe.expectMsgClass(m.runtimeClass.asInstanceOf[Class[T]])
        }

    protected def replyOfType[T](testKit: TestKit)
                                (implicit manifest: Manifest[T]): T = {
        val clazz = manifest.runtimeClass.asInstanceOf[Class[T]]
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
        val clazz = manifest.runtimeClass.asInstanceOf[Class[T]]
        new BePropertyMatcher[AnyRef] {
            def apply(left: AnyRef) =
                BePropertyMatchResult(clazz.isAssignableFrom(left.getClass),
                                      "an instance of " + clazz.getName)
        }
    }

    protected def drainProbe(testKit: TestKit) {
        while (testKit.msgAvailable) testKit.receiveOne(0.seconds)
    }

    def allProbes() = List(vtaProbe(), sProbe, flowProbe(), vtpProbe(),
            dpProbe(), dedupProbe(), discardPacketProbe, wflowAddedProbe,
            wflowRemovedProbe, packetInProbe, packetsEventsProbe,
            flowUpdateProbe, datapathEventsProbe, portsProbe)

    protected def drainProbes() {
        for (p <- allProbes()) { drainProbe(p) }
        for (p <- allProbes()) { p.receiveOne(50.milliseconds) }
    }

    def greTunnelId = dpController().underlyingActor.dpState
                                    .greOverlayTunnellingOutputAction
                                    .getPortNumber

    def parseTunnelActions(acts: Seq[FlowAction]):
            (Seq[FlowActionOutput], Seq[FlowKeyTunnel]) = {
        val outputs: Seq[FlowActionOutput] = acts
            .withFilter { _.isInstanceOf[FlowActionOutput] }
            .map { _.asInstanceOf[FlowActionOutput] }

        val tunnelKeys: Seq[FlowKeyTunnel] = acts
            .withFilter { _.isInstanceOf[FlowActionSetKey] }
            .map { _.asInstanceOf[FlowActionSetKey].getFlowKey.asInstanceOf[FlowKeyTunnel] }

        (outputs, tunnelKeys)
    }

    def tunnelIsLike(srcIp: Int, dstIp: Int, key: Long):
        FlowKeyTunnel => Boolean = { tunnelInfo =>
            tunnelInfo.ipv4_src == srcIp &&
            tunnelInfo.ipv4_dst == dstIp &&
            tunnelInfo.tun_id == key
        }

    def ackWCRemoved(until: Duration = timeout) =
        wflowRemovedProbe.fishForMessage(until, "WildcardFlowRemoved") {
            case WildcardFlowRemoved(_) => true
            case _ => false
        }.asInstanceOf[WildcardFlowRemoved].f

    def expectPacketIn() = expect[PacketIn].on(packetInProbe)

    def runFlowRemovalCallbacks(): Unit =
        deduplicationActor() ? HandlePackets(new Array(0))
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
