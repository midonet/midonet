/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import monitoring.{MonitoringActor, MonitoringAgent}
import scala.collection.JavaConversions._
import scala.collection.mutable
import java.util.UUID

import akka.testkit._
import akka.actor._
import akka.dispatch.{Future, Await}
import akka.util.Timeout
import akka.util.duration._

import com.google.inject.{AbstractModule, Module, Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest._
import org.scalatest.matchers.ShouldMatchers

import com.midokura.midolman.guice._
import com.midokura.midolman.guice.actors.{OutgoingMessage,
TestableMidolmanActorsModule}
import com.midokura.midolman.guice.config.MockConfigProviderModule
import com.midokura.midolman.guice.datapath.MockDatapathModule
import com.midokura.midolman.guice.reactor.ReactorModule
import com.midokura.midolman.guice.zookeeper.MockZookeeperConnectionModule
import services.{HostIdProviderService, MidolmanActorsService, MidolmanService}
import com.midokura.midonet.cluster.{DataClient, Client}
import com.midokura.midonet.cluster.services.MidostoreSetupService
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.netlink.protos.mocks.MockOvsDatapathConnectionImpl
import com.midokura.sdn.dp._
import topology.{VirtualTopologyActor, VirtualToPhysicalMapper}
import com.midokura.packets.Ethernet
import com.midokura.sdn.dp.flows.FlowKeyInPort
import com.midokura.midolman.DatapathController.InitializationComplete
import org.cassandraunit.utils.EmbeddedCassandraServerHelper


trait MidolmanTestCase extends Suite with BeforeAndAfterAll
    with BeforeAndAfter with OneInstancePerTest with ShouldMatchers with Dilation {

    var injector: Injector = null
    var mAgent: MonitoringAgent = null

    protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
      config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
      config.setProperty("midolman.enable_monitoring", "false")
      config.setProperty("cassandra.servers", "localhost:9171")
      config
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

    protected def newProbe(): TestProbe = {
        new TestProbe(actors())
    }

    protected def hostId(): UUID = {
        injector.getInstance(classOf[HostIdProviderService]).getHostId
    }

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra()
        val config = fillConfig(new HierarchicalConfiguration())
        injector = Guice.createInjector(getModulesAsJavaIterable(config))

        injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
        injector.getInstance(classOf[MidolmanService]).startAndWait()
        mAgent = injector.getInstance(classOf[MonitoringAgent])
        mAgent.startMonitoringIfEnabled()
        beforeTest()
    }

    after {
        EmbeddedCassandraServerHelper.stopEmbeddedCassandra()
        injector.getInstance(classOf[MidolmanService]).stopAndWait()
        if (mAgent != null) {
            mAgent.stop()
        }
        afterTest()
    }

    // These methods can be overridden by each class mixing MidolmanTestCase
    // to add custom operations before each test and after each tests
    protected def beforeTest() {}

    protected def afterTest() {}

    val probesByName = mutable.Map[String, TestKit]()
    val actorsByName = mutable.Map[String, TestActorRef[Actor]]()

    protected def getModulesAsJavaIterable(config: HierarchicalConfiguration) : java.lang.Iterable[Module] = {
      asJavaIterable(getModules(config))
    }

    protected def getModules(config: HierarchicalConfiguration): Iterable[Module] = {
        List(
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
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
            new MockClusterClientModule(),
            new TestableMidolmanActorsModule(probesByName, actorsByName),
            new MidolmanModule()
        )
    }

    def topActor[A <: Actor](name: String): ActorRef = {
        actors().actorFor(actors() / name)
    }

    protected def ask[T](actor: ActorRef, msg: Object): T = {
        val t = Timeout(1 second)
        val promise = akka.pattern.ask(actor, msg)(t).asInstanceOf[Future[T]]

        Await.result(promise, t.duration)
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

    protected def initializeDatapath(): DatapathController.InitializationComplete = {
        val result = ask[InitializationComplete](
            topActor(DatapathController.Name), Initialize())

        dpProbe().expectMsgType[Initialize] should not be null
        dpProbe().expectMsgType[OutgoingMessage] should not be null

        result
    }

    protected def getPortNumber(portName: String): Int = {
        dpController().underlyingActor.localPorts(portName).getPortNo
    }

    protected def triggerPacketIn(portName: String, ethPkt: Ethernet) {
        val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
            .addKey(new FlowKeyInPort().setInPort(getPortNumber(portName)))
        val dpPkt = new Packet()
            .setMatch(flowMatch)
            .setData(ethPkt.serialize())
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

    protected def dpProbe(): TestKit = {
        probeByName(DatapathController.Name)
    }

    protected def flowProbe(): TestKit = {
        probeByName(FlowController.Name)
    }

    protected def vtpProbe(): TestKit = {
        probeByName(VirtualToPhysicalMapper.Name)
    }

    protected def vtaProbe(): TestKit = {
        probeByName(VirtualTopologyActor.Name)
    }

    protected def simProbe(): TestKit = {
        probeByName(SimulationController.Name)
    }

    protected def requestOfType[T](testKit: TestKit)
                                  (implicit m: scala.reflect.Manifest[T]):T = {
        testKit.expectMsgClass(m.erasure.asInstanceOf[Class[T]])
    }

    protected def replyOfType[T](testKit: TestKit)
                                (implicit manifest:scala.reflect.Manifest[T]):T = {
        val clazz = manifest.erasure.asInstanceOf[Class[T]]
        val m = testKit.expectMsgClass(classOf[OutgoingMessage]).m
        assert(clazz.isInstance(m), "Reply should have been of type %s but was %s" format (clazz, m.getClass))
        clazz.cast(m)
    }

    protected def as[T](o: AnyRef)(implicit m: Manifest[T]): T = {
        val clazz = m.erasure.asInstanceOf[Class[T]]
        clazz.isInstance(o) should be(true)
        o.asInstanceOf[T]
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

