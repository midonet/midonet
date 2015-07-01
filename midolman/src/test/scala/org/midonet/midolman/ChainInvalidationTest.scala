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

import org.midonet.cluster.data.{Router => ClusterRouter, Chain}
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.services.{HostIdProviderService}
import org.midonet.midolman.simulation.{Router, Bridge}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
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
                      val clusterRouter: ClusterRouter, spec: MidolmanSpec) {

    var clusterBridge: UUID = _
    var vmPort: BridgePort = _
    var uplinkPort: BridgePort = _
    var routerPort: RouterPort = _

    val vmMac = MAC.random()
    val routerMac = MAC.random()

    val routerIp = new IPv4Subnet(subnet.getAddress.toInt + 1, subnet.getPrefixLen)
    val vmIp = IPv4Addr.fromInt(subnet.getAddress.toInt + 2)

    var vmPortInFilter: Chain = _
    var vmPortOutFilter: Chain = _
    var uplinkPortInFilter: Chain = _
    var uplinkPortOutFilter: Chain = _
    var bridgeInFilter: Chain = _
    var bridgeOutFilter: Chain = _
    var routerPortInFilter: Chain = _
    var routerPortOutFilter: Chain = _

    def bridge: Bridge = spec.fetchBridge(clusterBridge)
    def router: Router = spec.fetchDevice(clusterRouter)

    def tagFor(port: BridgePort) = FlowTagger.tagForBridgePort(clusterBridge, port.getId)
    def tagFor(port: RouterPort) = FlowTagger.tagForDevice(routerPort.getId)

    private def addAndMaterializeBridgePort(br: UUID): BridgePort = {
        val port = spec.newBridgePort(br)
        spec.stateStorage.setPortLocalAndActive(port.getId, spec.hostId, true)
        port
    }

    def buildTopology() {
        clusterBridge = spec.newBridge(s"bridge-$subnet")
        vmPort = spec.newBridgePort(clusterBridge)
        uplinkPort = spec.newBridgePort(clusterBridge)
        routerPort = spec.newRouterPort(clusterRouter, routerMac, routerIp)

        spec.clusterDataClient.portsLink(routerPort.getId, uplinkPort.getId)
        spec.materializePort(vmPort, spec.hostId, s"vm${BridgeWithOneVm.portIndex}")
        spec.newRoute(clusterRouter, "0.0.0.0", 0,
                      subnet.getAddress.toString, subnet.getPrefixLen,
                      NextHop.PORT, routerPort.getId,
                      new IPv4Addr(Route.NO_GATEWAY).toString, 100)

        bridgeInFilter = spec.newInboundChainOnBridge(s"b-${clusterBridge}-in", clusterBridge)
        bridgeOutFilter = spec.newOutboundChainOnBridge(s"b-${clusterBridge}-out", clusterBridge)

        vmPortInFilter = spec.newInboundChainOnPort(s"p-${vmPort.getId}-in", vmPort)
        vmPortOutFilter = spec.newOutboundChainOnPort(s"p-${vmPort.getId}-out", vmPort)

        uplinkPortInFilter = spec.newInboundChainOnPort(s"p-${uplinkPort.getId}-in", uplinkPort)
        uplinkPortOutFilter = spec.newOutboundChainOnPort(s"p-${uplinkPort.getId}-out", uplinkPort)

        routerPortInFilter = spec.newInboundChainOnPort(s"p-${routerPort.getId}-in", routerPort)
        routerPortOutFilter = spec.newOutboundChainOnPort(s"p-${routerPort.getId}-out", routerPort)

        spec.fetchTopology(vmPort, routerPort, uplinkPort,
            clusterRouter,
            bridgeInFilter, bridgeOutFilter, vmPortInFilter, vmPortOutFilter,
            uplinkPortInFilter, uplinkPortOutFilter, routerPortInFilter,
            routerPortOutFilter)
        spec.fetchBridge(clusterBridge)
        bridge.vlanMacTableMap(0.toShort).add(vmMac, vmPort.getId)
        feedArpCache(router, vmIp, vmMac)
    }
}


@RunWith(classOf[JUnitRunner])
class ChainInvalidationTest extends MidolmanSpec {

    var leftBridge: BridgeWithOneVm = _
    var rightBridge: BridgeWithOneVm = _

    val leftNet = new IPv4Subnet("10.100.100.0", 30)
    val rightNet = new IPv4Subnet("10.200.200.0", 30)

    var routerIn: Chain = _
    var routerInJump: Chain = _

    var clusterRouter: ClusterRouter = _

    private def buildTopology() {
        val host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).hostId)
        host should not be null

        clusterRouter = newRouter("router")
        clusterRouter should not be null

        routerIn = newInboundChainOnRouter("routerIn", clusterRouter)
        routerInJump = newChain("jumpChain", None)

        newJumpRuleOnChain(routerIn, 1, new Condition(), routerInJump.getId)

        leftBridge = new BridgeWithOneVm(leftNet, clusterRouter, this)
        leftBridge.buildTopology()
        rightBridge = new BridgeWithOneVm(rightNet, clusterRouter, this)
        rightBridge.buildTopology()
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor()))

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
        FlowTagger.tagForDevice(leftBridge.vmPortInFilter.getId),
        FlowTagger.tagForDevice(leftBridge.vmPort.getId))

    def tagsUntilLeftBridgeIn: Seq[FlowTag] = tagsUntilIngress ++ Seq(
        FlowTagger.tagForDevice(leftBridge.bridgeInFilter.getId),
        FlowTagger.tagForDevice(leftBridge.clusterBridge))

    def tagsUntilLeftBridgeOut: Seq[FlowTag] = tagsUntilLeftBridgeIn ++ Seq(
        FlowTagger.tagForDevice(leftBridge.bridgeOutFilter.getId))

    def tagsUntilLeftUplinkOut: Seq[FlowTag] = tagsUntilLeftBridgeOut ++ Seq(
        leftBridge.tagFor(leftBridge.uplinkPort),
        FlowTagger.tagForDevice(leftBridge.uplinkPortOutFilter.getId))

    def tagsUntilLeftRouterIn: Seq[FlowTag] = tagsUntilLeftUplinkOut ++ Seq(
        FlowTagger.tagForDevice(leftBridge.routerPort.getId),
        FlowTagger.tagForDevice(leftBridge.routerPortInFilter.getId))

    def tagsUntilRouterIn: Seq[FlowTag] = tagsUntilLeftRouterIn ++ Seq(
        FlowTagger.tagForDevice(clusterRouter.getId),
        FlowTagger.tagForDevice(routerIn.getId),
        FlowTagger.tagForDevice(routerInJump.getId))

    def tagsUntilRightRouterOut: Seq[FlowTag] = tagsUntilRouterIn ++ Seq(
        FlowTagger.tagForDevice(rightBridge.routerPort.getId))

    def tagsUntilRightUplinkIn: Seq[FlowTag] = tagsUntilRightRouterOut ++ Seq(
        FlowTagger.tagForDevice(rightBridge.uplinkPort.getId),
        FlowTagger.tagForDevice(rightBridge.uplinkPortInFilter.getId))

    def tagsUntilEgress: Seq[FlowTag] = tagsUntilRightUplinkIn ++ Seq(
        FlowTagger.tagForDevice(rightBridge.vmPort.getId),
        FlowTagger.tagForDevice(rightBridge.clusterBridge),
        FlowTagger.tagForDevice(rightBridge.bridgeOutFilter.getId),
        FlowTagger.tagForDevice(rightBridge.vmPortOutFilter.getId))

    def rightToLeftFrame = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr rightBridge.vmMac -> rightBridge.routerMac } <<
            { ip4 addr rightBridge.vmIp --> leftBridge.vmIp } <<
            { udp ports 53 ---> 53 } <<
            payload(UUID.randomUUID().toString)
    }

    scenario("flows are properly tagged") {
        When("a packet is sent across the topology")
        val packetContext = packetContextFor(leftToRightFrame, leftBridge.vmPort.getId)
        val result = simulate(packetContext)

        Then("It makes it to the other side, with all the expected tags")
        result should be (toPort(rightBridge.vmPort.getId)(tagsUntilEgress:_*))
    }

    def testChain(chain: Chain, tags: Seq[FlowTag]) {
        When("a drop rule is added to the chain")
        newLiteralRuleOnChain(chain, 1, new Condition(), RuleResult.Action.DROP)
        fetchDevice(chain)

        And("A packet is sent across the topology")
        val packetContext = packetContextFor(leftToRightFrame, leftBridge.vmPort.getId)
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
