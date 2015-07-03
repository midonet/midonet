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

import java.util.{HashSet, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.Router
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.rules.{RuleResult, NatTarget, Condition}
import org.midonet.midolman.simulation.{Bridge => SimBridge, Router => SimRouter}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class FloatingIpTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    val subnet1 = new IPv4Subnet("192.168.111.0", 24)
    val subnet2 = new IPv4Subnet("192.168.222.0", 24)

    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    val vm1Ip = IPv4Addr("192.168.111.2")
    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    val vm2Ip = IPv4Addr("192.168.222.2")
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")

    val floatingIp = IPv4Addr.fromString("10.0.173.5")

    var router: Router = _
    var brPort2 : BridgePort = _ // "Connected" to VM2
    var rtrPort1 : RouterPort = _ // "Connected" to VM1
    var rtrPort2 : RouterPort = _ // Interior port connecting to the brige

    override def beforeTest(): Unit = {
        router = newRouter("router")
        rtrPort1 = newRouterPort(router, routerMac1, subnet1)
        rtrPort1 should not be null
        materializePort(rtrPort1, hostId, "routerPort")

        newRoute(router, "0.0.0.0", 0,
                 subnet1.getAddress.toString, subnet1.getPrefixLen,
                 NextHop.PORT, rtrPort1.getId,
                 IPv4Addr(Route.NO_GATEWAY).toString, 10)

        rtrPort2 = newRouterPort(router, routerMac2, subnet2)

        newRoute(router, "0.0.0.0", 0,
                 subnet2.getAddress.toString, subnet2.getPrefixLen,
                 NextHop.PORT, rtrPort2.getId,
                 IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val bridge = newBridge("bridge")
        val brPort1 = newBridgePort(bridge)
        clusterDataClient.portsLink(rtrPort2.getId, brPort1.getId)

        brPort2 = newBridgePort(bridge)
        materializePort(brPort2, hostId, "VM2")

        val preChain = newInboundChainOnRouter("pre_routing", router)
        val postChain = newOutboundChainOnRouter("post_routing", router)

        // DNAT rule
        // assign floatingIP to VM1's private addr
        val dnatCond = new Condition()
        dnatCond.nwDstIp = new IPv4Subnet(floatingIp, 32)
        val dnatTarget = new NatTarget(vm1Ip.toInt,
                                       vm1Ip.toInt, 0, 0)
        newForwardNatRuleOnChain(preChain, 1, dnatCond, RuleResult.Action.ACCEPT,
                                 Set(dnatTarget), isDnat = true)

        // SNAT rules
        // assign floatingIP to VM1's private addr
        val snatCond = new Condition()
        snatCond.nwSrcIp = subnet1
        snatCond.outPortIds = new HashSet[UUID]()
        snatCond.outPortIds.add(rtrPort2.getId)
        val snatTarget = new NatTarget(floatingIp.toInt,
                                       floatingIp.toInt, 0, 0)
        newForwardNatRuleOnChain(postChain, 1, snatCond, RuleResult.Action.ACCEPT,
                                 Set(snatTarget), isDnat = false)

        fetchTopology(router, rtrPort1, rtrPort2, brPort1, brPort2,
                      preChain, postChain)
        fetchDevice[SimBridge](bridge)
    }

    def simRouter: SimRouter = fetchDevice(router)

    scenario("Floating IP") {
        feedArpTable(simRouter, vm1Ip, vm1Mac)
        feedArpTable(simRouter, vm2Ip, vm2Mac)

        // UDP VM2 -> floating IP, should be DNAT'ed
        var pkt = { eth src vm2Mac dst routerMac2 } <<
                  { ip4 src vm2Ip dst floatingIp } <<
                  { udp src 20301 dst 80 }

        val (simRes, pktCtx) = simulate(packetContextFor(pkt, brPort2.getId))
        simRes should be (AddVirtualWildcardFlow)

        pktCtx.wcmatch.getNetworkSrcIP should be (vm2Ip)
        pktCtx.wcmatch.getNetworkDstIP should be (vm1Ip)

        // UDP floating IP -> VM2, should be SNAT'ed
        pkt = { eth src vm1Mac dst routerMac1 } <<
              { ip4 src vm1Ip dst vm2Ip } <<
              { udp src 20301 dst 80 }
        
        val (simRes2, pktCtx2) = simulate(packetContextFor(pkt, rtrPort1.getId))
        simRes2 should be (AddVirtualWildcardFlow)

        pktCtx2.wcmatch.getNetworkSrcIP should be (floatingIp)
        pktCtx2.wcmatch.getNetworkDstIP should be (vm2Ip)

        // UDP VM2 -> VM1, private ips, no NAT
        pkt = { eth src vm2Mac dst routerMac2 } <<
              { ip4 src vm2Ip dst vm1Ip } <<
              { udp src 20301 dst 80 }

        val (simRes3, pktCtx3) = simulate(packetContextFor(pkt, brPort2.getId))
        simRes3 should be (AddVirtualWildcardFlow)

        pktCtx3.wcmatch.getNetworkSrcIP should be (vm2Ip)
        pktCtx3.wcmatch.getNetworkDstIP should be (vm1Ip)

        // ICMP echo, VM1 -> VM2, should be SNAT'ed
        pkt = { eth src vm1Mac dst routerMac1 } <<
              { ip4 src vm1Ip dst vm2Ip } <<
              { icmp.echo request }

        val (simRes4, pktCtx4) = simulate(packetContextFor(pkt, rtrPort1.getId))
        simRes4 should be (AddVirtualWildcardFlow)

        pktCtx4.wcmatch.getNetworkSrcIP should be (floatingIp)
        pktCtx4.wcmatch.getNetworkDstIP should be (vm2Ip)

        // ICMP echo, VM2 -> floating IP, should be DNAT'ed
        pkt = { eth src vm2Mac dst routerMac2 } <<
              { ip4 src vm2Ip dst floatingIp } <<
              { icmp.echo request }

        val (simRes5, pktCtx5) = simulate(packetContextFor(pkt, brPort2.getId))
        simRes5 should be (AddVirtualWildcardFlow)

        pktCtx5.wcmatch.getNetworkSrcIP should be (vm2Ip)
        pktCtx5.wcmatch.getNetworkDstIP should be (vm1Ip)

        // ICMP echo, VM1 -> floating IP, should be DNAT'ed, but not SNAT'ed
        pkt = { eth src vm1Mac dst routerMac1 } <<
              { ip4 src vm1Ip dst floatingIp } <<
              { icmp.echo request }

        val (simRes6, pktCtx6) = simulate(packetContextFor(pkt, rtrPort1.getId))
        simRes6 should be (AddVirtualWildcardFlow)

        pktCtx6.wcmatch.getNetworkSrcIP should be (vm1Ip)
        pktCtx6.wcmatch.getNetworkDstIP should be (vm1Ip)
    }
}
