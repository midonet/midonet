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
package org.midonet.midolman.simulation

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.actor.Actor.emptyBehavior
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Router => ClusterRouter, Bridge => ClusterBridge, Entity}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman._
import org.midonet.midolman.PacketWorkflow.{SimulationResult, AddVirtualWildcardFlow}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.DpPort
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.packets.ICMP.UNREACH_CODE
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class AdminStateTest extends MidolmanSpec {
    implicit val askTimeout: Timeout = 1 second

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
                                                  with MessageAccumulator),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
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

    var brPortIds: List[UUID] = List.empty

    override def beforeTest() {
        newHost("myself", hostId)
        bridge = newBridge("bridge0")
        router = newRouter("router0")

        interiorBridgePort = newBridgePort(bridge)
        exteriorBridgePort = newBridgePort(bridge)

        brPortIds = List(exteriorBridgePort.getId)

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

        brPortIds = List(exteriorBridgePort.getId)

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

        val topo = fetchTopology(bridge,
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

        sendPacket (fromBridgeSide) should be (flowMatching (emittedRouterSidePkt))
        sendPacket (fromRouterSide) should be (flowMatching (emittedBridgeSidePkt))
        PacketsEntryPoint.messages should be (empty)

        VirtualTopologyActor.getAndClear()

        VirtualToPhysicalMapper ! LocalPortActive(exteriorBridgePort.getId,
                                                  active = true)
    }

    lazy val fromBridgeSide = (exteriorBridgePort, bridgeSidePkt)
    lazy val bridgeSidePkt: Ethernet =
        { eth src macBridgeSide dst interiorRouterPort.getHwAddr } <<
        { ip4 src ipBridgeSide.toUnicastString dst ipRouterSide.toUnicastString }

    lazy val fromRouterSide = (exteriorRouterPort, routerSidePkt)
    lazy val routerSidePkt: Ethernet =
        { eth src macRouterSide dst exteriorRouterPort.getHwAddr } <<
        { ip4 src ipRouterSide.toUnicastString dst ipBridgeSide.toUnicastString }

    lazy val emittedBridgeSidePkt: Ethernet =
        { eth src interiorRouterPort.getHwAddr dst macBridgeSide } <<
            { ip4 src ipRouterSide.toUnicastString dst ipBridgeSide.toUnicastString }

    lazy val emittedRouterSidePkt: Ethernet =
        { eth src exteriorRouterPort.getHwAddr dst macRouterSide } <<
            { ip4 src ipBridgeSide.toUnicastString dst ipRouterSide.toUnicastString }


    feature("Devices with administrative state down don't process packets") {
        scenario("a down bridge drops packets silently") {
            Given("a down bridge")

            bridge.setAdminStateUp(false)
            clusterDataClient().bridgesUpdate(bridge)

            When("a packet is sent to that bridge")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(bridge.getId)
            })
        }

        scenario("a down interior bridge port drops egressing packets") {
            Given("a down bridge port")

            interiorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)

            When("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(interiorBridgePort.getId)
            })
        }

        scenario("a down interior bridge port drops ingressing packets") {
            Given("a down bridge port")

            interiorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(interiorBridgePort.getId)
            })
        }

        scenario("a down exterior bridge port drops egressing packets") {
            Given("a down bridge port")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            When("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(exteriorBridgePort.getId)
            })
        }

        scenario("a down exterior bridge port drops ingressing packets") {
            Given("a down bridge port")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(exteriorBridgePort.getId)
            })
        }

        scenario("a down exterior bridge port drops broadcast packets") {
            val ft = TestActorRef(new MockFlowTranslator()).underlyingActor

            Given("an exterior bridge port that is flooded")

            val f = ask(VirtualTopologyActor, BridgeRequest(bridge.getId))
            Await.result(f, Duration.Inf)
                 .asInstanceOf[Bridge]
                 .vlanMacTableMap(ClusterBridge.UNTAGGED_VLAN_ID)
                 .remove(macBridgeSide, exteriorBridgePort.getId)

            var pktCtx = packetContextFor(fromRouterSide._2, fromRouterSide._1.getId)
            var simRes = simulate (pktCtx)
            simRes should be (toBridge(bridge.getId, brPortIds))
            ft.translate(simRes) should contain (output(1).asInstanceOf[Any])

            When("the port is set to down")

            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            Then("the port should not be flooded")

            pktCtx = packetContextFor(fromRouterSide._2, fromRouterSide._1.getId)
            simRes = simulate(pktCtx)

            simRes should be (dropped())
            pktCtx.flowTags should contain(
                FlowTagger.tagForDevice(exteriorBridgePort.getId))
        }

        scenario("a down router sends an ICMP prohibited error") {
            Given("a down router")

            router.setAdminStateUp(false)
            clusterDataClient().routersUpdate(router)

            When("a packet is sent to that router")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(router.getId)
            })

            And("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down interior router port egressing packets sends an ICMP" +
                 " prohibited error from the ingressing port") {
            Given("a down router port")

            interiorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)

            When("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(interiorRouterPort.getId)
            })

            And("an ICMP prohibited error should be emitted from the " +
                    "ingressing port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort)
        }

        scenario("a down interior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            Given("a down router port")

            interiorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(interiorRouterPort.getId)
            })

            And("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down exterior router port egressing packets sends an " +
                 "ICMP prohibited error from the ingressing port") {
            Given("a down router port")

            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            When("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(exteriorRouterPort.getId)
            })

            And("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort)
        }

        scenario("a down exterior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            Given("a down router port")

            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForDevice(exteriorRouterPort.getId)
            })

            And("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort)
        }
    }

    class MockFlowTranslator extends Actor with ActorLogging with FlowTranslator {

        protected val datapathConnection: OvsDatapathConnection = null
        protected implicit val system = context.system

        val cookieStr: String = ""

        protected val dpState: DatapathState = new DatapathState {
            val host: rcu.Host = rcu.Host(hostId(), true, 0, "mido", Map.empty, Map.empty)
            def peerTunnelInfo(peer: UUID) = null
            def overlayTunnellingOutputAction: FlowActionOutput = null
            def vtepTunnellingOutputAction: FlowActionOutput = null
            def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
                Some(1)
            def getDpPortForInterface(itfName: String): Option[DpPort] = null
            def getVportForDpPortNumber(portNum: Integer): Option[UUID] = null
            def getDpPortName(num: Integer): Option[String] = null
            def version: Long = 0L
            def uplinkPid: Int = 0
            def isVtepTunnellingPort(portNumber: Short): Boolean = false
            def isOverlayTunnellingPort(portNumber: Short): Boolean = false
            override def getDescForInterface(itfName: String) = None
        }

        def translate(simRes: (SimulationResult, PacketContext)): Seq[FlowAction] = {
            val actions = simRes._1.asInstanceOf[AddVirtualWildcardFlow]
                                .flow.actions
            force(translateActions(simRes._2, actions))
        }

        def receive = emptyBehavior
    }

    private[this] def assertExpectedIcmpProhibitPacket(routerPort: RouterPort) {
        PacketsEntryPoint.messages should not be empty
        val msg = PacketsEntryPoint.messages.head.asInstanceOf[EmitGeneratedPacket]
        msg should not be null

        msg.egressPort should be(routerPort.getId)

        val ipPkt = msg.eth.getPayload.asInstanceOf[IPv4]
        ipPkt should not be null

        ipPkt.getProtocol should be(ICMP.PROTOCOL_NUMBER)

        val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt should not be null

        icmpPkt.getType should be (ICMP.TYPE_UNREACH)
        icmpPkt.getCode should be (UNREACH_CODE.UNREACH_FILTER_PROHIB.toByte)
    }

    feature("Setting the administrative state of a device should " +
            "invalidate all its flows") {
        scenario("the admin state of a bridge is set to down") {
            Given("a bridge with its state set to up")

            When("setting its state to down")

            bridge.setAdminStateUp(false)
            clusterDataClient().bridgesUpdate(bridge)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(bridge)
        }

        scenario("the admin state of a bridge is set to up") {
            Given("a bridge with its state set to down")
            bridge.setAdminStateUp(false)
            clusterDataClient().bridgesUpdate(bridge)
            VirtualTopologyActor.getAndClear()

            When("setting its state to down")

            bridge.setAdminStateUp(true)
            clusterDataClient().bridgesUpdate(bridge)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(bridge)
        }

        scenario("the admin state of a bridge port is set to down") {
            Given("interior and exterior bridge ports with state set to up")

            When("setting their state to down")

            interiorBridgePort.setAdminStateUp(false)
            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorBridgePort, exteriorBridgePort)
        }

        scenario("the admin state of a bridge port is set to up") {
            Given("interior and exterior bridge ports with their state set to down")
            interiorBridgePort.setAdminStateUp(false)
            exteriorBridgePort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorBridgePort)
            clusterDataClient().portsUpdate(exteriorBridgePort)
            VirtualTopologyActor.getAndClear()

            When("setting their state to up")

            interiorBridgePort.setAdminStateUp(true)
            exteriorBridgePort.setAdminStateUp(true)
            clusterDataClient().portsUpdate(interiorBridgePort)
            clusterDataClient().portsUpdate(exteriorBridgePort)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorBridgePort, exteriorBridgePort)
        }

        scenario("the admin state of a router is set to down") {
            Given("a router with its state set to up")

            When("setting its state to down")

            router.setAdminStateUp(false)
            clusterDataClient().routersUpdate(router)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(router)
        }

        scenario("the admin state of a router is set to up") {
            Given("a router with its state set to down")
            router.setAdminStateUp(false)
            clusterDataClient().routersUpdate(router)
            VirtualTopologyActor.getAndClear()

            When("setting its state to up")

            router.setAdminStateUp(true)
            clusterDataClient().routersUpdate(router)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(router)
        }

        scenario("the admin state of a router port is set to down") {
            Given("interior and exterior router ports with their state set to up")

            When("setting their state to down")

            interiorRouterPort.setAdminStateUp(false)
            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorRouterPort, exteriorRouterPort)
        }

        scenario("the admin state of a router port is set to up") {
            Given("interior and exterior router ports with their state set to down")
            interiorRouterPort.setAdminStateUp(false)
            exteriorRouterPort.setAdminStateUp(false)
            clusterDataClient().portsUpdate(interiorRouterPort)
            clusterDataClient().portsUpdate(exteriorRouterPort)
            VirtualTopologyActor.getAndClear()

            When("setting their state to up")

            interiorRouterPort.setAdminStateUp(true)
            exteriorRouterPort.setAdminStateUp(true)
            clusterDataClient().portsUpdate(interiorRouterPort)
            clusterDataClient().portsUpdate(exteriorRouterPort)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(interiorRouterPort, exteriorRouterPort)
        }
    }

    private[this] def assertFlowTagsInvalidated(devices: Entity.Base[UUID,_,_]*) {
        val tags = devices map (d =>
            FlowController.InvalidateFlowsByTag(
                FlowTagger.tagForDevice(d.getId)))

        val invalidations = VirtualTopologyActor.messages
                .filter(_.isInstanceOf[FlowController.InvalidateFlowsByTag])

        for (tag <- tags) {
            invalidations should contain (tag)
        }
    }
}
