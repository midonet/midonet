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

import org.midonet.midolman.simulation.{Router, Bridge}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.ArpCacheHelper._
import org.midonet.packets.{IPv4Addr, IPSubnet, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.layer3.Route
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.midolman.rules.{RuleResult, Condition}

object BridgeWithOneVm {
    private var _portIndex = -1
    def portIndex = {
        _portIndex += 1
        _portIndex
    }
}


class BridgeWithOneVm(val subnet: IPSubnet[IPv4Addr],
                      val clusterRouter: UUID, spec: MidolmanSpec) {

    var clusterBridge: UUID = _
    var vmPort: UUID = _
    var uplinkPort: UUID = _
    var routerPort: UUID = _

    val vmMac = MAC.random()
    val routerMac = MAC.random()

    val routerIp = new IPv4Subnet(subnet.getAddress.toInt + 1, subnet.getPrefixLen)
    val vmIp = IPv4Addr.fromInt(subnet.getAddress.toInt + 2)

    var vmPortInFilter: UUID = _
    var vmPortOutFilter: UUID = _
    var uplinkPortInFilter: UUID = _
    var uplinkPortOutFilter: UUID = _
    var bridgeInFilter: UUID = _
    var bridgeOutFilter: UUID = _
    var routerPortInFilter: UUID = _
    var routerPortOutFilter: UUID = _

    def bridge: Bridge = spec.fetchDevice[Bridge](clusterBridge)
    def router: Router = spec.fetchDevice[Router](clusterRouter)

    def tagFor(port: UUID) = FlowTagger.tagForBridgePort(clusterBridge, port)

    def buildTopology() {
        clusterBridge = spec.newBridge(s"bridge-$subnet")
        vmPort = spec.newBridgePort(clusterBridge)
        uplinkPort = spec.newBridgePort(clusterBridge)
        routerPort = spec.newRouterPort(clusterRouter, routerMac, routerIp)

        spec.linkPorts(routerPort, uplinkPort)
        spec.materializePort(vmPort, spec.hostId, s"vm${BridgeWithOneVm.portIndex}")
        spec.newRoute(clusterRouter, "0.0.0.0", 0,
                      subnet.getAddress.toString, subnet.getPrefixLen,
                      NextHop.PORT, routerPort,
                      new IPv4Addr(Route.NO_GATEWAY).toString, 100)

        bridgeInFilter = spec.newInboundChainOnBridge(s"b-${clusterBridge}-in", clusterBridge)
        bridgeOutFilter = spec.newOutboundChainOnBridge(s"b-${clusterBridge}-out", clusterBridge)

        vmPortInFilter = spec.newInboundChainOnPort(s"p-${vmPort}-in", vmPort)
        vmPortOutFilter = spec.newOutboundChainOnPort(s"p-${vmPort}-out", vmPort)

        uplinkPortInFilter = spec.newInboundChainOnPort(s"p-${uplinkPort}-in", uplinkPort)
        uplinkPortOutFilter = spec.newOutboundChainOnPort(s"p-${uplinkPort}-out", uplinkPort)

        routerPortInFilter = spec.newInboundChainOnPort(s"p-${routerPort}-in", routerPort)
        routerPortOutFilter = spec.newOutboundChainOnPort(s"p-${routerPort}-out", routerPort)

        spec.fetchChains(bridgeInFilter, bridgeOutFilter, vmPortInFilter, vmPortOutFilter,
                         uplinkPortInFilter, uplinkPortOutFilter, routerPortInFilter,
                         routerPortOutFilter)
        spec.fetchPorts(vmPort, routerPort, uplinkPort)
        spec.fetchDevice[Bridge](clusterBridge)
        spec.fetchDevice[Router](clusterRouter)
        bridge.vlanMacTableMap(0.toShort).add(vmMac, vmPort)
        feedArpCache(router, vmIp, vmMac)
    }
}


@RunWith(classOf[JUnitRunner])
class ChainInvalidationTest extends MidolmanSpec {

    var leftBridge: BridgeWithOneVm = _
    var rightBridge: BridgeWithOneVm = _

    val leftNet = new IPv4Subnet("10.100.100.0", 30)
    val rightNet = new IPv4Subnet("10.200.200.0", 30)

    var routerIn: UUID = _
    var routerInJump: UUID = _

    var clusterRouter: UUID = _

    private def buildTopology() {
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        routerIn = newInboundChainOnRouter("routerIn", clusterRouter)
        routerInJump = newChain("jumpChain", None)

        newJumpRuleOnChain(routerIn, 1, new Condition(), routerInJump)

        leftBridge = new BridgeWithOneVm(leftNet, clusterRouter, this)
        leftBridge.buildTopology()
        rightBridge = new BridgeWithOneVm(rightNet, clusterRouter, this)
        rightBridge.buildTopology()
    }

    override def beforeTest() {
        buildTopology()
    }

    def leftToRightFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftBridge.vmMac -> leftBridge.routerMac } <<
            { ip4 addr leftBridge.vmIp --> rightBridge.vmIp } <<
                { udp ports 53 ---> 53 } <<
                    payload(UUID.randomUUID().toString)
    }

    def tagsUntilIngress: Seq[FlowTag] = Seq(
        FlowTagger.tagForChain(leftBridge.vmPortInFilter),
        FlowTagger.tagForPort(leftBridge.vmPort))

    def tagsUntilLeftBridgeIn: Seq[FlowTag] = tagsUntilIngress ++ Seq(
        FlowTagger.tagForChain(leftBridge.bridgeInFilter),
        FlowTagger.tagForBridge(leftBridge.clusterBridge))

    def tagsUntilLeftBridgeOut: Seq[FlowTag] = tagsUntilLeftBridgeIn ++ Seq(
        FlowTagger.tagForChain(leftBridge.bridgeOutFilter))

    def tagsUntilLeftUplinkOut: Seq[FlowTag] = tagsUntilLeftBridgeOut ++ Seq(
        leftBridge.tagFor(leftBridge.uplinkPort),
        FlowTagger.tagForChain(leftBridge.uplinkPortOutFilter))

    def tagsUntilLeftRouterIn: Seq[FlowTag] = tagsUntilLeftUplinkOut ++ Seq(
        FlowTagger.tagForPort(leftBridge.routerPort),
        FlowTagger.tagForChain(leftBridge.routerPortInFilter))

    def tagsUntilRouterIn: Seq[FlowTag] = tagsUntilLeftRouterIn ++ Seq(
        FlowTagger.tagForRouter(clusterRouter),
        FlowTagger.tagForPort(routerIn),
        FlowTagger.tagForChain(routerInJump))

    def tagsUntilRightRouterOut: Seq[FlowTag] = tagsUntilRouterIn ++ Seq(
        FlowTagger.tagForPort(rightBridge.routerPort))

    def tagsUntilRightUplinkIn: Seq[FlowTag] = tagsUntilRightRouterOut ++ Seq(
        FlowTagger.tagForPort(rightBridge.uplinkPort),
        FlowTagger.tagForChain(rightBridge.uplinkPortInFilter))

    def tagsUntilEgress: Seq[FlowTag] = tagsUntilRightUplinkIn ++ Seq(
        FlowTagger.tagForPort(rightBridge.vmPort),
        FlowTagger.tagForBridge(rightBridge.clusterBridge),
        FlowTagger.tagForChain(rightBridge.bridgeOutFilter),
        FlowTagger.tagForChain(rightBridge.vmPortOutFilter))

    def rightToLeftFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr rightBridge.vmMac -> rightBridge.routerMac } <<
            { ip4 addr rightBridge.vmIp --> leftBridge.vmIp } <<
            { udp ports 53 ---> 53 } <<
            payload(UUID.randomUUID().toString)
    }

    scenario("flows are properly tagged") {
        When("a packet is sent across the topology")
        val packetContext = packetContextFor(leftToRightFrame, leftBridge.vmPort)
        val result = simulate(packetContext)

        Then("It makes it to the other side, with all the expected tags")
        result should be (toPort(rightBridge.vmPort)(tagsUntilEgress:_*))
    }

    def testChain(chain: UUID, tags: Seq[FlowTag]) {
        When("a drop rule is added to the chain")
        newLiteralRuleOnChain(chain, 1, new Condition(), RuleResult.Action.DROP)
        fetchChains(chain)

        And("A packet is sent across the topology")
        val packetContext = packetContextFor(leftToRightFrame, leftBridge.vmPort)
        val result = simulate(packetContext)

        Then("It makes it to the other side, with all the expected tags")
        result should be (dropped(tags:_*))
    }

    scenario("bridge port ingress chain is applied") {
        testChain(leftBridge.vmPortInFilter, tagsUntilIngress)
    }

    scenario("bridge ingress chain is applied") {
        testChain(leftBridge.bridgeInFilter, tagsUntilLeftBridgeIn)
    }

    scenario("bridge egress chain is applied") {
        testChain(leftBridge.bridgeOutFilter, tagsUntilLeftBridgeOut)
    }

    scenario("bridge interior port egress chain is applied") {
        testChain(leftBridge.uplinkPortOutFilter, tagsUntilLeftUplinkOut)
    }

    scenario("router interior port ingress chain is applied") {
        testChain(leftBridge.routerPortInFilter, tagsUntilLeftRouterIn)
    }

    scenario("router interior port egress chain is applied") {
        testChain(rightBridge.routerPortOutFilter, tagsUntilRightRouterOut)
    }

    scenario("bridge interior port ingress chain is applied") {
        testChain(rightBridge.uplinkPortInFilter, tagsUntilRightUplinkIn)
    }

    scenario("router ingress chain with jump rules is applied") {
        testChain(routerInJump, tagsUntilRouterIn)
    }
}
