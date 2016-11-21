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

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp.ports.{NetDevPort, VxLanTunnelPort}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.Actor.emptyBehavior
import akka.actor._
import akka.testkit.TestActorRef
import akka.util.Timeout

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{GeneratedLogicalPacket, SimulationResult}
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.packets.ICMP.UNREACH_CODE
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class AdminStateTest extends MidolmanSpec {
    implicit val askTimeout: Timeout = 1 second

    /*
     * The topology for this test consists of one bridge and one router,
     * each with two ports, one interior and another exterior.
     */

    val ipBridgeSide = new IPv4Subnet("10.0.0.64", 24)
    val macBridgeSide = MAC.random
    val ipRouterSide = new IPv4Subnet("10.0.1.128", 24)
    val macRouterSide = MAC.random

    var bridge: UUID = _
    var interiorBridgePort: UUID = _
    var exteriorBridgePort: UUID = _
    var router: UUID = _
    var interiorRouterPort: UUID = _
    var exteriorRouterPort: UUID = _

    var brPortIds: List[UUID] = List.empty
    var backChannel: SimulationBackChannel = _

    override def beforeTest() {
        bridge = newBridge("bridge0")
        router = newRouter("router0")

        interiorBridgePort = newBridgePort(bridge)
        exteriorBridgePort = newBridgePort(bridge)

        brPortIds = List(exteriorBridgePort)

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

        brPortIds = List(exteriorBridgePort)

        newRoute(router,
            "0.0.0.0", 0,
            ipBridgeSide.toUnicastString, ipBridgeSide.getPrefixLen,
            Route.NextHop.PORT, interiorRouterPort,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        newRoute(router,
            "0.0.0.0", 0,
            ipRouterSide.toUnicastString, ipRouterSide.getPrefixLen,
            Route.NextHop.PORT, exteriorRouterPort,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val topo = fetchPorts(interiorBridgePort,
                              exteriorRouterPort,
                              interiorRouterPort,
                              exteriorRouterPort)
        val r = fetchDevice[Router](router)
        feedArpTable(r, ipBridgeSide.getAddress, macBridgeSide)
        feedArpTable(r, ipRouterSide.getAddress, macRouterSide)

        val simBridge = fetchDevice[Bridge](bridge)
        feedMacTable(simBridge, macBridgeSide, exteriorBridgePort)

        sendPacket (fromBridgeSide) should be (flowMatching (emittedRouterSidePkt))
        sendPacket (fromRouterSide) should be (flowMatching (emittedBridgeSidePkt))

        backChannel = simBackChannel
        getAndClearBC(simBackChannel)

        VirtualToPhysicalMapper.setPortActive(exteriorBridgePort,
                                              portNumber = -1,
                                              active = true,
                                              tunnelKey = 0L)
    }

    def getAndClearBC(backChannel: SimulationBackChannel)
            : mutable.Buffer[BackChannelMessage] = {
        val messages = mutable.Buffer[BackChannelMessage]()
        while (backChannel.hasMessages)
            messages += backChannel.poll()
        messages
    }

    lazy val fromBridgeSide = (exteriorBridgePort, bridgeSidePkt)
    lazy val bridgeSidePkt: Ethernet =
        { eth src macBridgeSide dst fetchDevice[RouterPort](interiorRouterPort).portMac } <<
        { ip4 src ipBridgeSide.toUnicastString dst ipRouterSide.toUnicastString }

    lazy val fromRouterSide = (exteriorRouterPort, routerSidePkt)
    lazy val routerSidePkt: Ethernet =
        { eth src macRouterSide dst fetchDevice[RouterPort](exteriorRouterPort).portMac } <<
        { ip4 src ipRouterSide.toUnicastString dst ipBridgeSide.toUnicastString }

    lazy val emittedBridgeSidePkt: Ethernet =
        { eth src fetchDevice[RouterPort](interiorRouterPort).portMac dst macBridgeSide } <<
            { ip4 src ipRouterSide.toUnicastString dst ipBridgeSide.toUnicastString }

    lazy val emittedRouterSidePkt: Ethernet =
        { eth src fetchDevice[RouterPort](exteriorRouterPort).portMac dst macRouterSide } <<
            { ip4 src ipBridgeSide.toUnicastString dst ipRouterSide.toUnicastString }


    feature("Devices with administrative state down don't process packets") {
        scenario("a down bridge drops packets silently") {
            Given("a down bridge")

            setBridgeAdminStateUp(bridge, false)

            When("a packet is sent to that bridge")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForBridge(bridge)
            })
        }

        scenario("a down interior bridge port drops egressing packets") {
            Given("a down bridge port")

            setPortAdminStateUp(interiorBridgePort, false)

            When("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(interiorBridgePort)
            })
        }

        scenario("a down interior bridge port drops ingressing packets") {
            Given("a down bridge port")

            setPortAdminStateUp(interiorBridgePort, false)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(interiorBridgePort)
            })
        }

        scenario("a down exterior bridge port drops egressing packets") {
            Given("a down bridge port")

            setPortAdminStateUp(exteriorBridgePort, false)

            When("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(exteriorBridgePort)
            })
        }

        scenario("a down exterior bridge port drops ingressing packets") {
            Given("a down bridge port")

            setPortAdminStateUp(exteriorBridgePort, false)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(exteriorBridgePort)
            })
        }

        scenario("a down exterior bridge port drops broadcast packets") {
            val ft = TestActorRef(new MockFlowTranslator()).underlyingActor

            Given("an exterior bridge port that is flooded")

            val f = VirtualTopology.get(classOf[Bridge], bridge)
            val simBridge = Await.result(f, Duration.Inf).asInstanceOf[Bridge]
            clearMacTable(simBridge, macBridgeSide, exteriorBridgePort)

            var pktCtx = packetContextFor(fromRouterSide._2, fromRouterSide._1)
            var simRes = simulate (pktCtx)
            simRes should be (toBridge(simBridge, brPortIds))
            ft.translate(simRes) should contain (output(1).asInstanceOf[Any])

            When("the port is set to down")

            setPortAdminStateUp(exteriorBridgePort, false)

            Then("the port should not be flooded")

            pktCtx = packetContextFor(fromRouterSide._2, fromRouterSide._1)
            simRes = simulate(pktCtx)

            simRes should be (dropped())
            pktCtx.flowTags should contain(
                FlowTagger.tagForPort(exteriorBridgePort))
        }

        scenario("a down router sends an ICMP prohibited error") {
            Given("a down router")

            setRouterAdminStateUp(router, false)

            When("a packet is sent to that router")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForRouter(router)
            })

            And("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort, flow._2)
        }

        scenario("a down interior router port egressing packets sends an ICMP" +
                 " prohibited error from the ingressing port") {
            Given("a down router port")

            setPortAdminStateUp(interiorRouterPort, false)

            When("a packet is egressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(interiorRouterPort)
            })

            And("an ICMP prohibited error should be emitted from the " +
                    "ingressing port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort, flow._2)
        }

        scenario("a down interior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            Given("a down router port")

            setPortAdminStateUp(interiorRouterPort, false)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(interiorRouterPort)
            })

            And("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort, flow._2)
        }

        scenario("a down exterior router port egressing packets sends an " +
                 "ICMP prohibited error from the ingressing port") {
            Given("a down router port")

            setPortAdminStateUp(exteriorRouterPort, false)

            When("a packet is egressing that port")

            val flow = sendPacket (fromBridgeSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(exteriorRouterPort)
            })

            And("an ICMP prohibited error should be emitted from the " +
                "ingressing port")

            assertExpectedIcmpProhibitPacket(interiorRouterPort, flow._2)
        }

        scenario("a down exterior router port ingressing packets sends an " +
                 "ICMP prohibited error") {
            Given("a down router port")

            setPortAdminStateUp(exteriorRouterPort, false)

            When("a packet is ingressing that port")

            val flow = sendPacket (fromRouterSide)

            Then("a drop flow should be installed")

            flow should be (dropped {
                FlowTagger.tagForPort(exteriorRouterPort)
            })

            And("an ICMP prohibited error should be emitted from the port")

            assertExpectedIcmpProhibitPacket(exteriorRouterPort, flow._2)
        }
    }

    class MockFlowTranslator extends Actor with ActorLogging with FlowTranslator {

        protected val datapathConnection: OvsDatapathConnection = null

        val cookieStr: String = ""

        override protected val hostId: UUID = AdminStateTest.this.hostId

        override protected val vt = injector.getInstance(classOf[VirtualTopology])

        override protected val config: MidolmanConfig = AdminStateTest.this.config

        override protected val numWorkers: Int = 1
        override protected val workerId: Int = 0

        protected val dpState: DatapathState = new DatapathState {
            val host = Host(hostId, true, Map.empty, Map.empty)
            override def peerTunnelInfo(peer: UUID) = null
            override def dpPortForTunnelKey(key: Long) = null
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getDpPortNumberForVport(vportId: UUID): Integer = 1
            override def getVportForDpPortNumber(portNum: Integer): UUID = null
            override def isVtepTunnellingPort(portNumber: Int): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Int): Boolean = false
            override def datapath: Datapath = new Datapath(0, "midonet")
            override def tunnelRecircVxLanPort: VxLanTunnelPort = null
            override def hostRecircPort: NetDevPort = null
            override def tunnelRecircOutputAction: FlowActionOutput = null
            override def hostRecircOutputAction: FlowActionOutput = null
            override def isFip64TunnellingPort(portNumber: Int): Boolean = false
            override def tunnelFip64VxLanPort: VxLanTunnelPort = null
            override def fip64TunnellingOutputAction: FlowActionOutput = null
            override def setFip64PortKey(port: UUID, key: Int): Unit = {}
            override def clearFip64PortKey(port: UUID, key: Int): Unit = {}
            override def getFip64PortForKey(key: Int): UUID = null
        }

        def translate(simRes: (SimulationResult, PacketContext)): Seq[FlowAction] = {
            force {
                simRes._2.flowActions.clear()
                translateActions(simRes._2)
            }
            simRes._2.flowActions.toList
        }

        def receive = emptyBehavior
    }

    private[this] def assertExpectedIcmpProhibitPacket(routerPort: UUID,
                                                       context: PacketContext): Unit = {
        val generatedPacket = context.backChannel.find[GeneratedLogicalPacket]()
        generatedPacket.egressPort should be(routerPort)

        val ipPkt = generatedPacket.eth.getPayload.asInstanceOf[IPv4]
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

            setBridgeAdminStateUp(bridge, false)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForBridge(bridge))
        }

        scenario("the admin state of a bridge is set to up") {
            Given("a bridge with its state set to down")
            setBridgeAdminStateUp(bridge, false)
            getAndClearBC(backChannel)

            When("setting its state to down")

            setBridgeAdminStateUp(bridge, true)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForBridge(bridge))
        }

        scenario("the admin state of a bridge port is set to down") {
            Given("interior and exterior bridge ports with state set to up")

            When("setting their state to down")

            setPortAdminStateUp(interiorBridgePort, false)
            setPortAdminStateUp(exteriorBridgePort, false)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForPort(interiorBridgePort),
                                      FlowTagger.tagForPort(exteriorBridgePort))
        }

        scenario("the admin state of a bridge port is set to up") {
            Given("interior and exterior bridge ports with their state set to down")

            setPortAdminStateUp(interiorBridgePort, false)
            setPortAdminStateUp(exteriorBridgePort, false)

            getAndClearBC(backChannel)

            When("setting their state to up")

            setPortAdminStateUp(interiorBridgePort, true)
            setPortAdminStateUp(exteriorBridgePort, true)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForPort(interiorBridgePort),
                                      FlowTagger.tagForPort(exteriorBridgePort))
        }

        scenario("the admin state of a router is set to down") {
            Given("a router with its state set to up")

            When("setting its state to down")

            setRouterAdminStateUp(router, false)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForRouter(router))
        }

        scenario("the admin state of a router is set to up") {
            Given("a router with its state set to down")
            setRouterAdminStateUp(router, false)
            getAndClearBC(backChannel)

            When("setting its state to up")

            setRouterAdminStateUp(router, true)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForRouter(router))
        }

        scenario("the admin state of a router port is set to down") {
            Given("interior and exterior router ports with their state set to up")

            When("setting their state to down")

            setPortAdminStateUp(interiorRouterPort, false)
            setPortAdminStateUp(exteriorRouterPort, false)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForPort(interiorRouterPort),
                                      FlowTagger.tagForPort(exteriorRouterPort))
        }

        scenario("the admin state of a router port is set to up") {
            Given("interior and exterior router ports with their state set to down")
            setPortAdminStateUp(interiorRouterPort, false)
            setPortAdminStateUp(exteriorRouterPort, false)
            getAndClearBC(backChannel)

            When("setting their state to up")

            setPortAdminStateUp(interiorRouterPort, true)
            setPortAdminStateUp(exteriorRouterPort, true)

            Then("corresponding flows should be invalidated")

            assertFlowTagsInvalidated(FlowTagger.tagForPort(interiorRouterPort),
                                      FlowTagger.tagForPort(exteriorRouterPort))
        }
    }

    private[this] def assertFlowTagsInvalidated(tags: FlowTagger.FlowTag*) {
        val invalidations = getAndClearBC(backChannel)
                .filter(_.isInstanceOf[FlowTagger.FlowTag])

        for (tag <- tags) {
            invalidations should contain (tag)
        }
    }

}
