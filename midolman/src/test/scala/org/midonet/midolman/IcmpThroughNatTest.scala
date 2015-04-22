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
package org.midonet.midolman

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.{Router => ClusterRouter}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.rules.{NatTarget, RuleResult, Condition}
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.simulation.Coordinator.ToPortAction
import org.midonet.midolman.simulation.{Bridge, Router}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.ArpCacheHelper._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{IPv4Subnet, MAC, IPv4Addr}
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}

@RunWith(classOf[JUnitRunner])
class IcmpThroughNatTest extends MidolmanSpec {
    val leftNet = "192.168.1.0"
    val leftNetmask = 24

    val leftMac = MAC.fromString("02:02:01:10:10:aa")
    val leftIp = "192.168.1.1"

    val rtLeftMac = MAC.fromString("02:02:01:10:10:dd")
    //val rtLeftIp = "192.168.1.240"
    val rtLeftIp = new IPv4Subnet(leftNet, leftNetmask)

    val rightNet = "192.168.2.0"
    val rightNetmask = 24

    val rightMac = MAC.fromString("02:02:02:20:20:aa")
    val rightIp = "192.168.2.1"

    val rtRightMac = MAC.fromString("02:02:02:20:bb:aa")
    //val rtRightIp = "192.168.2.240"
    val rtRightIp = new IPv4Subnet(rightNet, rightNetmask)

    var leftPort: BridgePort = null
    var rightPort: BridgePort = null
    var rtLeftPort: RouterPort = null
    var rtRightPort: RouterPort = null
    var clusterBridge: ClusterBridge = null
    var clusterRouter: ClusterRouter = null

    private def setActive(id: UUID) {
        stateStorage.setPortLocalAndActive(id, hostId, true)
    }

    private def buildTopology() {
        val host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).getHostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        leftPort = newBridgePort(clusterBridge)
        rightPort = newBridgePort(clusterBridge)

        rtLeftPort = newRouterPort(clusterRouter, rtLeftMac, rtLeftIp)
        rtRightPort = newRouterPort(clusterRouter, rtRightMac, rtRightIp)

        val ports = List(leftPort, rightPort, rtLeftPort, rtRightPort)
        ports map{ _.getId } foreach setActive

        fetchTopology(leftPort, rightPort, rtLeftPort, rtRightPort,
                      clusterRouter, clusterBridge)

        val router = fetchDevice[Router](clusterRouter)
        feedArpCache(router, IPv4Addr(leftIp), leftMac)
        feedArpCache(router, IPv4Addr(rightIp), rightMac)

        newRoute(clusterRouter,
                 "0.0.0.0", 0,
                 leftNet, leftNetmask,
                 Route.NextHop.PORT,
                 rtLeftPort.getId,
                 new IPv4Addr(Route.NO_GATEWAY).toString,
                 1)

        newRoute(clusterRouter,
                 "0.0.0.0", 0,
                 rightNet, rightNetmask,
                 Route.NextHop.PORT,
                 rtRightPort.getId,
                 new IPv4Addr(Route.NO_GATEWAY).toString,
                 1)


        val rtrInChain = newInboundChainOnRouter("inChain", clusterRouter)

        val snatTarget =  new NatTarget(IPv4Addr.stringToInt(leftIp),
                                        IPv4Addr.stringToInt(rightIp), 80, 80)

        val snatRule = newForwardNatRuleOnChain(rtrInChain, 1, Condition.TRUE,
                  RuleResult.Action.CONTINUE, Set(snatTarget), isDnat = false)
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                                  with MessageAccumulator))

    implicit var natTx: FlowStateTransaction[NatKey, NatBinding] = _

    override def beforeTest() {
        buildTopology()
        val natTable = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
        natTx = new FlowStateTransaction(natTable)
    }

    def icmpEchoReqL2R = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr leftMac -> rightMac } <<
            { ip4 addr leftIp --> rightIp } <<
                (icmp.echo.request id 0x200 seq 0x120 data "data")
    }

    def icmpEchoRepR2L = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr rightMac -> leftMac } <<
            { ip4 addr rightIp --> leftIp } <<
                (icmp.echo.reply id 0x200 seq 0x120 data "data")
    }

    def icmpEchoReqL2RViaRouter = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr leftMac -> rtLeftMac } <<
            { ip4 addr leftIp --> rightIp } <<
                (icmp.echo.request id 0x200 seq 0x120 data "data")
    }

    feature("ICMP packets traversing bridges do not trigger l4 fields tagging") {

        scenario("a VM sends an ICMP echo request to another VM") {
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(rightMac, rightPort.getId)

            When("an icmp echo req is sent across the bridge")
            val (pktContext, action) =
                simulateDevice(bridge, icmpEchoReqL2R, leftPort.getId)

            Then("the bridge send the packet to the target vm")
            action should be (ToPortAction(rightPort.getId))

            And("The FlowMatch's icmp id field is not tagged")
            pktContext.wcmatch.userspaceFieldsSeen shouldBe false
        }

        scenario("the other VM sends back an Icmp echo reply") {
            val bridge: Bridge = fetchDevice(clusterBridge)
            val macTable = bridge.vlanMacTableMap(0.toShort)
            macTable.add(leftMac, leftPort.getId)

            When("an icmp echo reply is sent across the bridge")
            val (pktContext, action) =
                simulateDevice(bridge, icmpEchoRepR2L, rightPort.getId)

            Then("the bridge send the packet back to the first VM")
            action should be (ToPortAction(leftPort.getId))

            And("The FlowMatch's icmp id field is not tagged")
            pktContext.wcmatch.userspaceFieldsSeen shouldBe false
        }
    }

    feature("ICMP packets going through NAT rules triggers l4 fields tagging") {

        scenario("an ICMP request goes through a router with a SNAT rule ") {

            val router = fetchDevice[Router](clusterRouter)

            When("an icmp request is sent across the router between two VMs")
            val (pktContext, action) =
                simulateDevice(router, icmpEchoReqL2RViaRouter, rtLeftPort.getId)

            Then("the router sends the packet to the target port")
            action should be (ToPortAction(rtRightPort.getId))

            And("The FlowMatch's icmp id field is tagged as seen")
            pktContext.wcmatch.userspaceFieldsSeen shouldBe true
        }
    }
}
