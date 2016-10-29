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

import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, ShortDrop}
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.{Bridge, Router}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.util.{MidolmanSpec, TestDatapathState}
import org.midonet.odp.OpenVSwitch
import org.midonet.odp.flows.{FlowActionPopVLAN, FlowActionPushVLAN, FlowActionSetKey, FlowKeyEthernet}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.VirtualActions.{Decap, Encap}
import org.midonet.sdn.state.{FlowStateTransaction, ShardedFlowStateTable}

@RunWith(classOf[JUnitRunner])
class RouterPeeringTest extends MidolmanSpec {
    val vni = 21

    val localVmMac = MAC.random()
    val localVmIp = IPv4Addr("192.168.1.1")
    val remoteVmIp = IPv4Addr("192.168.2.1")
    val remoteSubnet = new IPv4Subnet("192.168.2.1", 24)

    val exteriorTenantPortMac = MAC.random()
    val exteriorTenantPortIp = new IPv4Subnet("192.168.1.0", 24)
    var exteriorTenantPort: UUID = _
    var localTenantRouter: UUID = _
    val interiorTenantPortIp = new IPv4Subnet("84.123.1.0", 24)
    var interiorTenantPort: UUID =_
    val interiorTenantPortMac = MAC.random()

    val gwIp = IPv4Addr.random

    var tenantBridgePort: UUID = _
    var bridge: UUID = _
    var vtepBridgePort: UUID = _

    val interiorVtepPortIp = new IPv4Subnet("92.123.1.0", 24)
    var interiorVtepPort: UUID = _
    var localVtepRouter: UUID = _
    var exteriorVtepPort: UUID = _
    val exteriorVtepPortIp = new IPv4Subnet("115.123.1.0", 24)
    val exteriorVtepPortMac = MAC.random()
    val interiorVtepPortMac = MAC.random()

    val vtepTunnelIp = IPv4Addr.random
    val remoteVtepRouterIp = new IPv4Addr("115.123.1.35")
    val remoteVtepRouterMac = MAC.random
    val remoteTenantRouterPortMac = MAC.random

    override def beforeTest(): Unit = {
        localTenantRouter = newRouter("tenant-router")
        exteriorTenantPort = newRouterPort(
            localTenantRouter,
            exteriorTenantPortMac,
            exteriorTenantPortIp)
        interiorTenantPort = newRouterPort(
            localTenantRouter,
            interiorTenantPortMac,
            interiorTenantPortIp)
        newRoute(localTenantRouter,
            exteriorTenantPortIp.toNetworkAddress.toString,
            exteriorTenantPortIp.getPrefixLen,
            remoteSubnet.toNetworkAddress.toString,
            remoteSubnet.getPrefixLen,
            Route.NextHop.PORT, interiorTenantPort,
            gwIp.toString, 10)
        newRoute(localTenantRouter,
            remoteVmIp.toString, 32,
            localVmIp.toString, 32,
            Route.NextHop.PORT, exteriorTenantPort,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        feedArpTable(fetchDevice[Router](localTenantRouter), localVmIp, localVmMac)

        bridge = newBridge("bridge")
        tenantBridgePort = newBridgePort(bridge)
        vtepBridgePort = newBridgePort(bridge)
        feedArpCache(fetchDevice[Bridge](bridge), gwIp, remoteTenantRouterPortMac)
        feedMacTable(
            fetchDevice[Bridge](bridge),
            remoteTenantRouterPortMac,
            vtepBridgePort)

        localVtepRouter = newRouter("vtep-router")
        interiorVtepPort = newL2RouterPort(
            localVtepRouter,
            interiorVtepPortMac,
            interiorVtepPortIp,
            vni,
            vtepTunnelIp)
        feedPeeringTable(interiorVtepPort, remoteTenantRouterPortMac, remoteVtepRouterIp)
        exteriorVtepPort = newRouterPort(
            localVtepRouter,
            exteriorVtepPortMac,
            exteriorVtepPortIp)
        newRoute(
            localVtepRouter,
            vtepTunnelIp.toString, 32,
            remoteVtepRouterIp.toString, 32,
            Route.NextHop.PORT, exteriorVtepPort,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        feedArpTable(
            fetchDevice[Router](localVtepRouter),
            remoteVtepRouterIp,
            remoteVtepRouterMac)

        linkPorts(interiorTenantPort, tenantBridgePort)
        linkPorts(vtepBridgePort, interiorVtepPort)

        materializePort(exteriorTenantPort, hostId, "port0")
        materializePort(exteriorVtepPort, hostId, "port1")
    }

    feature("Packets are sent to a remote site") {
        scenario("Packets are correctly encapsulted") {
            val pkt = { eth src MAC.random() dst exteriorTenantPortMac } <<
                      {ip4 src localVmIp dst remoteVmIp } <<
                      { tcp src 123 dst 80 }
            val (simRes, pktCtx) = sendPacket(exteriorTenantPort, pkt)
            simRes should be (AddVirtualWildcardFlow)

            pktCtx.recircMatch should not be null

            (pktCtx.virtualFlowActions.collectFirst {
                case setKey: FlowActionSetKey if isEthernet(setKey) =>
                    setKey.getFlowKey.asInstanceOf[FlowKeyEthernet].eth_dst
            } map MAC.fromAddress get) should be (remoteTenantRouterPortMac)

            (pktCtx.virtualFlowActions.collectFirst {
                case Encap(vni) => vni
            } get) should be (vni)

            pktCtx.wcmatch.getEthSrc should be (exteriorVtepPortMac)
            pktCtx.wcmatch.getEthDst should be (remoteVtepRouterMac)
            pktCtx.wcmatch.getNetworkSrcIP should be (vtepTunnelIp)
            pktCtx.wcmatch.getNetworkDstIP should be (remoteVtepRouterIp)
            pktCtx.wcmatch.getDstPort should be (UDP.VXLAN)
        }
        scenario("Packets with similar flows should have unique src port") {
            val usedPorts = ListBuffer[Int]()
            for (i <- 61000 to 62000) {
                val pkt = { eth src MAC.random() dst exteriorTenantPortMac } <<
                           { ip4 src localVmIp dst remoteVmIp } <<
                           { tcp src i.toShort dst 80 }
                val (simRes, pktCtx) = sendPacket(exteriorTenantPort, pkt)
                val port = pktCtx.wcmatch.getSrcPort
                port should be < 65535
                port should be > 0
                usedPorts should not contain port
                usedPorts += port
            }
        }
    }

    feature("Packets are received from a remote site") {
        scenario("Packets are correctly decapsulted") {
            val pkt = { eth src MAC.random() dst exteriorVtepPortMac } <<
                      { ip4 src remoteVtepRouterIp dst vtepTunnelIp } <<
                      { udp src 123 dst UDP.VXLAN.toShort } <<
                      { vxlan vni vni } <<
                      { eth src MAC.random() dst interiorTenantPortMac } <<
                      { ip4 src remoteVmIp dst localVmIp } <<
                      { tcp src 80 dst 123 }

            val (simRes, pktCtx) = sendPacket(exteriorVtepPort, pkt)
            simRes should be (AddVirtualWildcardFlow)

            pktCtx.recircMatch should not be null

            (pktCtx.virtualFlowActions.collectFirst {
                case Decap(vni) => vni
            } get) should be (vni)

            pktCtx.wcmatch.getEthSrc should be (exteriorTenantPortMac)
            pktCtx.wcmatch.getEthDst should be (localVmMac)
            pktCtx.wcmatch.getNetworkSrcIP should be (remoteVmIp)
            pktCtx.wcmatch.getNetworkDstIP should be (localVmIp)
            pktCtx.wcmatch.getSrcPort should be (80)
            pktCtx.wcmatch.getDstPort should be (123)
        }

        scenario("Packets not correctly addressed are not decapsulated") {
            val pkt = { eth src MAC.random() dst exteriorVtepPortMac } <<
                      { ip4 src remoteVtepRouterIp dst IPv4Addr.random } <<
                      { udp src 123 dst UDP.VXLAN.toShort } <<
                      { vxlan vni vni } <<
                      { eth src MAC.random() dst interiorTenantPortMac } <<
                      { ip4 src remoteVmIp dst localVmIp } <<
                      { tcp src 80 dst 123 }

            val (simRes, pktCtx) = sendPacket(exteriorVtepPort, pkt)
            simRes should be (ShortDrop)

            (pktCtx.virtualFlowActions.collectFirst {
                case Decap(vni) => vni
            } isDefined) should be (false)
        }

        scenario("Packets with the wrong vni are not decapsulated") {
            val pkt = { eth src MAC.random() dst exteriorVtepPortMac } <<
                      { ip4 src remoteVtepRouterIp dst IPv4Addr.random } <<
                      { udp src 123 dst UDP.VXLAN.toShort } <<
                      { vxlan vni 9 } <<
                      { eth src MAC.random() dst interiorTenantPortMac } <<
                      { ip4 src remoteVmIp dst localVmIp } <<
                      { tcp src 80 dst 123 }

            val (simRes, pktCtx) = sendPacket(exteriorVtepPort, pkt)
            simRes should be (ShortDrop)

            (pktCtx.virtualFlowActions.collectFirst {
                case Decap(vni) => vni
            } isDefined) should be (false)
        }
    }

    feature("conntrack with router peering") {
        scenario("connection tracking should apply to inner packet") {
            val router = newRouter("vtep-router")
            val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]()
                    .addShard()
            implicit val conntrackTx = new FlowStateTransaction(conntrackTable)

            val rp1Mac = MAC.random
            val rp1 = newRouterPort(router, rp1Mac, "1.1.1.2", "1.1.1.0", 24, vni = 100,
                                    tunnelIp = Some(IPv4Addr("2.2.2.2")))

            val chain = newInboundChainOnPort("router port chain", rp1)

            val fwdCond = new Condition()
            fwdCond.matchForwardFlow = true
            newLiteralRuleOnChain(chain, 1, fwdCond, RuleResult.Action.ACCEPT)

            feedPeeringTable(rp1, rp1Mac, IPv4Addr.fromString("5.5.5.5"))

            val ttl = 2.toByte
            val fromMac = MAC.random()
            val fromIp = "4.4.4.4"
            val toIp = "1.1.1.3"
            val pkt = { eth src fromMac dst rp1Mac} <<
                      { ip4 src fromIp dst toIp ttl ttl } <<
                      { icmp.echo request }

            newRoute(router, "0.0.0.0", 0, "1.1.1.0", 24, NextHop.PORT,
                    rp1, null, 2)

            val (simRes, pktCtx) = simulate(packetContextFor(pkt, rp1))

            val dstIp = pktCtx.recircMatch.getNetworkDstIP.asInstanceOf[IPv4Addr]
            val srcIp = pktCtx.recircMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr]

            dstIp.toString shouldBe toIp
            srcIp.toString shouldBe fromIp

            val innerKey = EgressConnTrackKey(pktCtx.recircMatch, rp1)
            val outerKey = EgressConnTrackKey(pktCtx.wcmatch, rp1)

            pktCtx.trackConnection(rp1)

            conntrackTx.get(innerKey) shouldBe RETURN_FLOW
            conntrackTx.get(outerKey) shouldBe null
        }

        scenario("Inner packets is conntracked if decapsulated") {
            val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]()
                .addShard()
            implicit val conntrackTx = new FlowStateTransaction(conntrackTable)

            val chain = newInboundChainOnPort("router port chain", exteriorVtepPort)

            val fwdCond = new Condition()
            fwdCond.matchForwardFlow = true
            newLiteralRuleOnChain(chain, 1, fwdCond, RuleResult.Action.ACCEPT)

            val pkt = { eth src MAC.random() dst exteriorVtepPortMac } <<
                { ip4 src remoteVtepRouterIp dst vtepTunnelIp } <<
                { udp src 123 dst UDP.VXLAN.toShort } <<
                { vxlan vni vni } <<
                { eth src MAC.random() dst interiorTenantPortMac } <<
                { ip4 src remoteVmIp dst localVmIp } <<
                { tcp src 80 dst 123 }

            val (simRes, pktCtx) = simulate(packetContextFor(pkt, exteriorVtepPort))
            simRes should be (AddVirtualWildcardFlow)

            pktCtx.recircMatch should not be null

            val innerKey = EgressConnTrackKey(pktCtx.recircMatch, interiorTenantPort)
            val outerKey = EgressConnTrackKey(pktCtx.wcmatch, interiorTenantPort)

            pktCtx.trackConnection(interiorTenantPort)

            conntrackTx.get(innerKey) shouldBe null
            conntrackTx.get(outerKey) shouldBe RETURN_FLOW
        }
    }

    feature("Interaction with VLAN-aware bridge") {
        scenario("Encapsulated packets that egressed a VLAN port should have " +
                 "VLAN ID removed.") {
            val vab = newBridge("vlan-aware-bridge")
            val vabExtPort = newBridgePort(vab)
            materializePort(vabExtPort, hostId, "vab-ext-port")

            val vabBridgePort = newBridgePort(vab, vlanId = Some(100))
            val bridgeVabPort = newBridgePort(bridge)
            linkPorts(vabBridgePort, bridgeVabPort)
            feedMacTable(
                fetchDevice[Bridge](vab),
                remoteTenantRouterPortMac,
                vabBridgePort)

            val fromMac = MAC.random()
            val pkt = { eth src fromMac dst remoteTenantRouterPortMac vlan 100 } <<
                      { ip4 src localVmIp dst remoteVmIp ttl 10.toByte } <<
                      { tcp src 123 dst 80 }

            val ctx = packetContextFor(pkt, vabExtPort)
            ctx.origMatch.setEtherType(IPv4.ETHERTYPE)
            ctx.wcmatch.setEtherType(IPv4.ETHERTYPE)
            val (simRes, _) = simulate(ctx)
            simRes shouldBe AddVirtualWildcardFlow

            val translator = new TestFlowTranslator(new TestDatapathState)
            translator.translateActions(ctx)

            ctx.recircPayload.getVlanIDs shouldBe empty
            ctx.recircFlowActions
                .exists(_.isInstanceOf[FlowActionPopVLAN]) shouldBe true
        }

        scenario("Encapsulated packets that ingressed a VLAN port should " +
                 "have VLAN ID added.") {
            val vab = newBridge("vlan-aware-bridge")
            val vabExtPort = newBridgePort(vab)
            materializePort(vabExtPort, hostId, "vab-ext-port")

            val vabBridgePort = newBridgePort(vab, vlanId = Some(100))
            val bridgeVabPort = newBridgePort(bridge)
            linkPorts(vabBridgePort, bridgeVabPort)

            val dstMac = MAC.random()
            feedMacTable(
                fetchDevice[Bridge](vab),
                dstMac, vabExtPort)

            val pkt = { eth src MAC.random() dst exteriorVtepPortMac } <<
                      { ip4 src remoteVtepRouterIp dst vtepTunnelIp } <<
                      { udp src 123 dst UDP.VXLAN.toShort } <<
                      { vxlan vni vni } <<
                      { eth src MAC.random() dst dstMac } <<
                      { ip4 src remoteVmIp dst localVmIp } <<
                      { tcp src 80 dst 123 }

            val ctx = packetContextFor(pkt, exteriorVtepPort)
            ctx.origMatch.setEtherType(IPv4.ETHERTYPE)
            ctx.wcmatch.setEtherType(IPv4.ETHERTYPE)
            val (simRes, _) = simulate(ctx)
            simRes shouldBe AddVirtualWildcardFlow

            val translator = new TestFlowTranslator(new TestDatapathState)
            translator.translateActions(ctx)

            ctx.flowActions.find(_.isInstanceOf[FlowActionPushVLAN]) match {
                case Some(pushVlan: FlowActionPushVLAN) =>
                    pushVlan.getTagControlIdentifier & 0x7ff shouldBe 100
                case _ => fail("FlowActionPushVLAN not found.")
            }
            ctx.packetActions.find(_.isInstanceOf[FlowActionPushVLAN]) match {
                case Some(pushVlan: FlowActionPushVLAN) =>
                    pushVlan.getTagControlIdentifier & 0x7ff shouldBe 100
                case _ => fail("FlowActionPushVLAN not found.")
            }
        }
    }


    private def isEthernet(setKey: FlowActionSetKey) =
        setKey.getFlowKey.attrId() == OpenVSwitch.FlowKey.Attr.Ethernet
}
