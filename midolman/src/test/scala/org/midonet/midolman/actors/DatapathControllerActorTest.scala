/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import akka.actor._
import akka.testkit._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner

import org.midonet.config.ConfigProvider
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.topology.HostConfigOperation
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager
import org.midonet.odp.Datapath
import org.midonet.odp.DpPort
import org.midonet.odp.ports._

@RunWith(classOf[JUnitRunner])
class DatapathControllerActorTest extends TestKit(ActorSystem("DPCActorTest"))
                                  with ImplicitSender with Suite
                                  with FeatureSpecLike with Matchers {

    import DatapathController._
    import DatapathController.Internal._
    import PacketWorkflow.AddVirtualWildcardFlow
    import VirtualToPhysicalMapper.GreZoneChanged
    import VirtualToPhysicalMapper.GreZoneMembers

    val dpc = TestActorRef[DatapathController]("TestDPCActor")

    val emptyJSet = new java.util.HashSet[InterfaceDescription]()

    val dpPortGre = new GreTunnelPort("gre")
    val dpPortInt = new InternalPort("int")
    val dpPortDev = new NetDevPort("eth0")

    val portRequests = List[DpPortRequest](
        DpPortCreateNetdev(dpPortDev, None),
        DpPortDeleteNetdev(dpPortDev, None)
    )

    val portReplies =
        portRequests.map{ req => DpPortCreateSuccess(req, req.port, 1) } ++
            portRequests.map{ DpPortError(_, null) }

    val miscMessages = List[AnyRef](
        DpPortStatsRequest(UUID.randomUUID),
        InterfacesUpdate(emptyJSet),
        GreZoneChanged(UUID.randomUUID, null, HostConfigOperation.Added),
        GreZoneMembers(UUID.randomUUID, Set()),
        AddVirtualWildcardFlow(null, Nil, Set())
    )

    val commonMessages = List[AnyRef](
        Initialize,
        Host(UUID.randomUUID, "midonet", Map(), Map())
    )

    val initOnlyMessages = List[AnyRef](
        ExistingDatapathPorts(null, Set(dpPortGre,dpPortInt)),
        DatapathClear,
        TunnelPortsReady(null, null)
    )

    val allMessages = commonMessages ++ portRequests ++ portReplies ++ miscMessages
    val initMessages = commonMessages ++ initOnlyMessages

    val conf1 = ConfigProvider.providerForIniConfig(new HierarchicalConfiguration)
                              .getConfig(classOf[MidolmanConfig])

    val hierachConfig = new HierarchicalConfiguration
    hierachConfig setProperty("datapath.vxlan_udp_port", 4444)
    val conf2 = ConfigProvider.providerForIniConfig(hierachConfig)
                              .getConfig(classOf[MidolmanConfig])

    feature("Datapath Initialization Actor receive messages") {
        val initReceive = dpc.underlyingActor.DatapathInitializationActor

        for (m <- initMessages) {
            scenario(" should accept message " + m) {
                initReceive.isDefinedAt(m) should be(true)
            }
        }

        for (m <- allMessages.filter{ !initMessages.contains(_) }) {
            scenario(" should not accept message " + m) {
                initReceive.isDefinedAt(m) should be(false)
            }
        }

        scenario("The DPC does not accept naked Strings") {
            initReceive.isDefinedAt("foo") should be(false)
        }
    }

    feature("Datapath Controller Actor receive messages") {
        val normalReceive = dpc.underlyingActor.DatapathControllerActor
        for (m <- allMessages) {
            scenario(" should accept message " + m) {
                normalReceive.isDefinedAt(m) should be(true)
            }
        }

        scenario("The DPC does not accept naked Strings") {
            normalReceive.isDefinedAt("foo") should be(false)
        }
    }

    feature("Datapath Initialization Actor sets up tunnel ports") {

        def prepareDPC(conf: MidolmanConfig = conf1) = {
            val dpcInit = TestActorRef(new DatapathControllerInit(self))
            dpcInit.underlyingActor.midolmanConfig = conf
            dpcInit.underlyingActor.upcallConnManager =
                new MockUpcallDatapathConnectionManager(conf)
            (dpcInit, dpcInit.underlyingActor)
        }

        def ackTunnelPorts() {
            (1 to 2) foreach { _ =>
                expectMsgPF() {
                    case _: GreTunnelPort => true
                    case _: VxLanTunnelPort => true
                }
            }
        }

        scenario("sends tunnel creation requests after a clear msg") {
            val (dpcInit, instance) = prepareDPC()
            instance.upcallConnManager = new MockManager(self)
            dpcInit ! DatapathClear
            expectMsgType[GreTunnelPort]
            expectMsg(CompleteInit)
            expectNoMsg(Duration fromNanos 10000)
            instance.dpState.tunnelGre should not be (None)
            instance.dpState.tunnelVxLan shouldBe None
        }

        scenario("The DPC only creates a vxlan port when the udp port is set") {
            val (dpcInit, instance) = prepareDPC(conf2)
            instance.upcallConnManager = new MockManager(self)
            dpcInit ! DatapathClear
            ackTunnelPorts()
            expectMsg(CompleteInit)
            expectNoMsg(Duration fromNanos 10000)
            instance.dpState.tunnelGre should not be (None)
            instance.dpState.tunnelVxLan should not be (None)
        }

        scenario("The DPC retries when the port creation fails") {
            val (dpcInit, instance) = prepareDPC(conf2)
            instance.upcallConnManager = new FailOnceManager(self)
            dpcInit ! DatapathClear
            Thread sleep 1000
            ackTunnelPorts()
            expectMsg(CompleteInit)
            expectNoMsg(Duration fromNanos 10000)
            instance.dpState.tunnelGre should not be (None)
            instance.dpState.tunnelVxLan should not be (None)
        }
    }

    class MockManager(testKit: ActorRef) extends UpcallDatapathConnectionManager {
        def createAndHookDpPort(dp: Datapath, port: DpPort)
                (implicit ec: ExecutionContext, as: ActorSystem) = {
            testKit ! port
            Future.successful((DpPort.fakeFrom(port, 0), 0))
        }

        def deleteDpPort(datapath: Datapath, port: DpPort)
                (implicit ec: ExecutionContext, as: ActorSystem) = Future(true)
    }

    class FailOnceManager(testKit: ActorRef) extends UpcallDatapathConnectionManager {
        var ports = Set.empty[DpPort]
        def createAndHookDpPort(dp: Datapath, port: DpPort)
                (implicit ec: ExecutionContext, as: ActorSystem) = {
            if (ports contains port) {
                testKit ! port
                Future.successful((DpPort.fakeFrom(port, 0), 0))
            } else {
                ports += port
                Future.failed(new IllegalArgumentException("fake error"))
            }
        }

        def deleteDpPort(datapath: Datapath, port: DpPort)
                (implicit ec: ExecutionContext, as: ActorSystem) = Future(true)
    }

    object CompleteInit

    class DatapathControllerInit(testKit: ActorRef) extends DatapathController {
        override def completeInitialization() { testKit ! CompleteInit }
    }
}
