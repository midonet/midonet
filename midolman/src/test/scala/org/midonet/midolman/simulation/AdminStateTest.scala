/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.simulation

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.dispatch.{ExecutionContext, Await}
import akka.util.{Timeout, Duration}
import akka.util.duration._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.midolman._
import org.midonet.cluster.data.{Router => ClusterRouter, Bridge => ClusterBridge, Entity, Port}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, rcu, FlowTagger, VirtualTopologyActor}
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.midolman.util.MockCache
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp
import org.midonet.odp.flows.{FlowActions, FlowAction}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets._
import org.midonet.packets.ICMP.UNREACH_CODE
import org.midonet.sdn.flows.WildcardMatch

@RunWith(classOf[JUnitRunner])
class AdminStateTest extends Suite
                     with FeatureSpec
                     with ShouldMatchers
                     with GivenWhenThen
                     with CustomMatchers
                     with MockMidolmanActors
                     with VirtualConfigurationBuilders
                     with MidolmanServices
                     with VirtualTopologyHelper
                     with OneInstancePerTest {

    implicit val askTimeout: Timeout = 3 minutes

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
                                       with MessageAccumulator),
        VirtualToPhysicalMapper -> (() => new MockVirtualToPhysicalMapper
                                          with MessageAccumulator))

    /*
     * The topology for this test consists of one bridge and one router,
     * each with two ports, one interior and another exterior.
     */

    val ipBridgeSide = new IPv4Subnet("10.0.0.64", 24)
    val macBridgeSide = MAC.random
    val ipRouterSide = new IPv4Subnet("10.0.1.128", 24)
    val macRouterSide = MAC.random

    var bridge: ClusterBridge = _
    var interiorBridgePort: BridgePort = _
    var exteriorBridgePort: BridgePort = _
    var router: ClusterRouter = _
    var interiorRouterPort: RouterPort = _
    var exteriorRouterPort: RouterPort = _

    override def beforeTest() {
        newHost("myself", hostId)
        bridge = newBridge("bridge0")
        router = newRouter("router0")

        interiorBridgePort = newBridgePort(bridge)
        exteriorBridgePort = newBridgePort(bridge)

        val routerInteriorPortIp = new IPv4Subnet("10.0.0.254", 24)
        interiorRouterPort = newRouterPort(router,
            MAC.random(),
            routerInteriorPortIp.toUnicastString,
            routerInteriorPortIp.toNetworkAddress.toString,
            routerInteriorPortIp.getPrefixLen)

        val routerExteriorPortIp = new IPv4Subnet("10.0.1.254", 24)
        exteriorRouterPort = newRouterPort(router,
            MAC.random(),
            routerExteriorPortIp.toUnicastString,
            routerExteriorPortIp.toNetworkAddress.toString,
            routerExteriorPortIp.getPrefixLen)

        linkPorts(interiorBridgePort, interiorRouterPort)

        materializePort(exteriorBridgePort, hostId, "port0")
        materializePort(exteriorRouterPort, hostId, "port1")

        newRoute(router,
            "0.0.0.0", 0,
            ipBridgeSide.toUnicastString, ipBridgeSide.getPrefixLen,
            Route.NextHop.PORT, interiorRouterPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        newRoute(router,
            "0.0.0.0", 0,
            ipRouterSide.toUnicastString, ipRouterSide.getPrefixLen,
            Route.NextHop.PORT, exteriorRouterPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val topo = preloadTopology(bridge,
                                   interiorBridgePort,
                                   exteriorRouterPort,
                                   router,
                                   interiorRouterPort,
                                   exteriorRouterPort)

        val arpTable = topo.collect({ case r: Router => r.arpTable}).head
        arpTable.set(ipBridgeSide.getAddress, macBridgeSide)
        arpTable.set(ipRouterSide.getAddress, macRouterSide)

        topo.collect({ case b: Bridge => b.vlanMacTableMap})
            .head(ClusterBridge.UNTAGGED_VLAN_ID)
            .add(macBridgeSide, exteriorBridgePort.getId)

        sendPacket (fromBridgeSide) should be (flowMatching (bridgeSidePkt))
        sendPacket (fromRouterSide) should be (flowMatching (routerSidePkt))
        DeduplicationActor.messages should be ('empty)

        VirtualTopologyActor.getAndClear()
    }

    lazy val fromBridgeSide = (exteriorBridgePort, bridgeSidePkt)
    lazy val bridgeSidePkt: Ethernet =
        { eth src macBridgeSide dst interiorRouterPort.getHwAddr } <<
        { ip4 src ipBridgeSide.toUnicastString dst ipRouterSide.toUnicastString }

    lazy val fromRouterSide = (exteriorRouterPort, routerSidePkt)
    lazy val routerSidePkt: Ethernet =
        { eth src macRouterSide dst exteriorRouterPort.getHwAddr } <<
        { ip4 src ipRouterSide.toUnicastString dst ipBridgeSide.toUnicastString }

    feature("Devices with administrative state down don't process packets") {
        scenario("a down bridge drops packets silently") {
            given("a down bridge")

            bridge.setAdminStateUp(false)
            clusterDataClient().bridgesUpdate(bridge)

            when("a packet is sent to that bridge")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)
        }

        scenario("a down interior bridge port drops egressing packets") {
            given("a down bridge port")

            interiorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)

            when("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)
        }

        scenario("a down interior bridge port drops ingressing packets") {
            given("a down bridge port")

            interiorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)

            when("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            then("a drop flow should be installed")

            flow should be (dropped)
        }

        scenario("a down exterior bridge port drops egressing packets") {
            given("a down bridge port")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            when("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            then("a drop flow should be installed")

            flow should be (dropped)
        }

        scenario("a down exterior bridge port drops ingressing packets") {
            given("a down bridge port")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            when("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)
        }

        scenario("a down exterior bridge port drops broadcast packets") {
            val ft = actorSystem.actorOf(Props(new MockFlowTranslator))
            def translateActions(simRes: SimulationResult) = {
                val actions = simRes.asInstanceOf[AddVirtualWildcardFlow]
                                    .flow.actions
                Await.result(ask(ft, actions), Duration.Inf)
                     .asInstanceOf[Seq[Any]]
            }

            given("an exterior bridge port that is flooded")

            val f = ask(VirtualTopologyActor,
                        BridgeRequest(bridge.getId, update = false))
            Await.result(f, Duration.Inf)
                 .asInstanceOf[Bridge]
                 .vlanMacTableMap(ClusterBridge.UNTAGGED_VLAN_ID)
                 .remove(macBridgeSide, exteriorBridgePort.getId)

            var simRes = sendPacket (fromRouterSide)

            simRes should be (toPortSet(bridge.getId))
            var tacts = translateActions(simRes)
            tacts should contain (FlowActions.output(1).asInstanceOf[Any])

            when("the port is set to down")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            then("the port should not be flooded")

            simRes = sendPacket (fromRouterSide)
            simRes should be (toPortSet(bridge.getId))

            tacts = translateActions(simRes)
            tacts should not (contain (FlowActions.output(1).asInstanceOf[Any]))
        }

        scenario("a down router sends an ICMP prohibited error") {
            given("a down router")

            router.setAdminStateUp(false)
            clusterDataClient().routersUpdate(router)

            when("a packet is sent to that router")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)

            and("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down interior router port egressing packets sends an ICMP" +
                 "prohibited error from the ingressing port") {
            given("a down router port")

            interiorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)

            when("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            then("a drop flow should be installed")

            flow should be (dropped)

            and("an ICMP prohibited error should be emitted from the " +
                    "ingressing port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort)
        }

        scenario("a down interior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            given("a down router port")

            interiorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)

            when("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)

            and("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down exterior router port egressing packets sends an " +
                 "ICMP prohibited error from the ingressing port") {
            given("a down router port")

            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            when("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            then("a drop flow should be installed")

            flow should be (dropped)

            and("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down exterior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            given("a down router port")

            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            when("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            then("a drop flow should be installed")

            flow should be (dropped)

            and("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort)
        }
    }

    class MockVirtualToPhysicalMapper extends Actor {
        def receive = {
            case PortSetRequest(portSetId, _) =>
                sender ! new PortSet(portSetId, Set.empty[UUID],
                    Set(exteriorBridgePort.getId))
        }
    }

    class MockFlowTranslator extends Actor with ActorLogging
                                           with FlowTranslator {
        protected val datapathConnection: OvsDatapathConnection = null
        implicit protected val requestReplyTimeout =
            new Timeout(5, TimeUnit.SECONDS)

        protected val dpState: DatapathState = new DatapathState {
            def host: rcu.Host = null
            def peerTunnelInfo(peer: UUID): Option[(Int, Int)] = null
            def tunnelGre: Option[odp.Port[_, _]] = None
            def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
                Some(1)
            def getDpPortForInterface(itfName: String): Option[odp.Port[_, _]] = null
            def getVportForDpPortNumber(portNum: Integer): Option[UUID] = null
            def getDpPortName(num: Integer): Option[String] = null
            def version: Long = 0L
        }

        def receive = {
            case actions: Seq[FlowAction[_]] =>
                val s = sender
                translateActions(actions, None, None, null) map {
                    s ! _
                }
        }
    }

    private[this] def assertExpectedIcmpProhibitPacket(routerPort: RouterPort) {
        val msg = DeduplicationActor.messages.head.asInstanceOf[EmitGeneratedPacket]
        msg should not be null

        msg.egressPort should be(routerPort.getId)

        val ipPkt = msg.eth.getPayload.asInstanceOf[IPv4]
        ipPkt should not be null

        ipPkt.getProtocol should be(ICMP.PROTOCOL_NUMBER)

        val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt should not be null

        icmpPkt.getType should be (ICMP.TYPE_UNREACH)
        icmpPkt.getCode should be (UNREACH_CODE.UNREACH_FILTER_PROHIB.toChar)
    }

    feature("Setting the administrative state of a device should " +
            "invalidate all its flows") {
        scenario("the admin state of a bridge is set to down") {
            given("a bridge")

            when("setting its state to down")

            bridge.setAdminStateUp(false)
            clusterDataClient().bridgesUpdate(bridge)

            then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(bridge)
        }

        scenario("the admin state of a bridge port is set to down") {
            given("interior and exterior bridge ports")

            when("setting their state to down")

            interiorBridgePort.setAdminStateUp(false)
            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorBridgePort)
            assertFlowTagsInvalidated(exteriorBridgePort)
        }

        scenario("the admin state of a router is set to down") {
            given("a router")

            when("setting its state to down")

            router.setAdminStateUp(false)
            clusterDataClient().routersUpdate(router)

            then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(router)
        }

        scenario("the admin state of a router port is set to down") {
            given("interior and exterior router ports")

            when("setting their state to down")

            interiorRouterPort.setAdminStateUp(false)
            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorRouterPort)
            assertFlowTagsInvalidated(exteriorRouterPort)
        }
    }

    private[this] def assertFlowTagsInvalidated(devices: Entity.Base[UUID,_,_]*) {
        val tags = devices map (d =>
            FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateFlowsByDevice(d.getId)))

        val invalidations = VirtualTopologyActor.messages
                .filter(_.isInstanceOf[FlowController.InvalidateFlowsByTag])

        // TODO: use contain allOf when ScalaTest gets updated
        tags foreach { t: Any => invalidations should contain (t) }
    }

    private[this] def sendPacket(t: (Port[_,_], Ethernet)): SimulationResult =
        Await.result(new Coordinator(
            makeWMatch(t._1, t._2),
            t._2,
            Some(1),
            None,
            0,
            new MockCache(),
            new MockCache(),
            new MockCache(),
            None,
            Nil)(implicitly[ExecutionContext],
                 implicitly[ActorSystem],
                 null)
            .simulate(), Duration.Inf)

    private[this] def makeWMatch(port: Port[_,_], pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
                     .setInputPortUUID(port.getId)
}