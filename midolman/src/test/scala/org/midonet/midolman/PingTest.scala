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

import java.util.{LinkedList, UUID}

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, NoOp}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.simulation.{Bridge, Router}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.odp.flows.FlowKeys
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnBridge
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort

@RunWith(classOf[JUnitRunner])
class PingTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    // Router port one connecting to host VM1
    var simRouter : Router = _

    // External router port
    var rtrPort1 : RouterPort = null
    val routerIp1 = new IPv4Subnet("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    val rtrPort1DpNum = 1

    // Interior router port connecting to bridge
    var rtrPort2 : RouterPort = _
    val routerIp2 = new IPv4Subnet("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")

    // VM1: remote host to ping
    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    val vm1Ip = new IPv4Subnet("192.168.111.2", 24)

    // Bridge (connects VM2 to interior router port)
    var bridge : UUID = _

    // VM2: local host to ping
    val vm2Ip = new IPv4Subnet("192.168.222.2", 24)
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")
    var vm2Port : BridgePort = null
    val vm2PortDpNum = 2
    val vm2PortName = "VM2"
    var vm2PortNumber = 0


    override def beforeTest(): Unit = {
        val router = newRouter("router")
        router should not be null

        // set up materialized port on router
        rtrPort1 = newRouterPort(router, routerMac1, routerIp1)
        rtrPort1 should not be null
        materializePort(rtrPort1, hostId, "routerPort")

        newRoute(router, "0.0.0.0", 0, routerIp1.toUnicastString,
                 routerIp1.getPrefixLen, NextHop.PORT, rtrPort1.getId,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // set up logical port on router
        rtrPort2 = newRouterPort(router, routerMac2, routerIp2)
        rtrPort2 should not be null

        newRoute(router, "0.0.0.0", 0, routerIp2.toUnicastString,
                 routerIp2.getPrefixLen, NextHop.PORT, rtrPort2.getId,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // create bridge link to router's logical port
        bridge = newBridge("bridge")
        bridge should not be null

        val brPort1 = newBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient.portsLink(rtrPort2.getId, brPort1.getId)

        // add a materialized port on bridge, logically connected to VM2
        vm2Port = newBridgePort(bridge)
        vm2Port should not be null
        materializePort(vm2Port, hostId, vm2PortName)

        fetchTopology(router, rtrPort1, rtrPort2, brPort1, vm2Port)
        fetchDevice[Bridge](bridge)

        simRouter = fetchDevice(router)
    }

    def matchIcmp(generatedPackets: LinkedList[GeneratedPacket], egressPort: UUID,
                  fromMac: MAC, toMac: MAC,
                  fromIp: IPv4Addr, toIp: IPv4Addr, `type`: Byte,
                  code: Byte): Unit = {
        generatedPackets should have size 1
        generatedPackets.get(0).egressPort should be (egressPort)
        val genPkt = generatedPackets.get(0).eth
        genPkt.getSourceMACAddress should be (fromMac)
        genPkt.getDestinationMACAddress should be (toMac)
        val genIp = genPkt.getPayload.asInstanceOf[IPv4]
        genIp.getSourceIPAddress should be (fromIp)
        genIp.getDestinationIPAddress should be (toIp)
        val genIcmp = genIp.getPayload.asInstanceOf[ICMP]
        genIcmp.getCode should be (code)
        genIcmp.getType should be (`type`)
    }

    scenario("Ping internal router port") {
        feedArpTable(simRouter, vm2Ip.getAddress(), vm2Mac)

        val generatedPackets = new LinkedList[GeneratedPacket]()

        val pkt = { eth src vm2Mac dst routerMac2 } <<
            { ip4 src vm2Ip.getAddress dst routerIp2.getAddress } <<
            { icmp.echo request }

        val (res, _) = simulate(packetContextFor(pkt, vm2Port.getId,
                                                 generatedPackets))
        res should be (NoOp)

        matchIcmp(generatedPackets, rtrPort2.getId, routerMac2, vm2Mac,
                  routerIp2.getAddress, vm2Ip.getAddress,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Ping external router port") {
        feedArpTable(simRouter, vm2Ip.getAddress(), vm2Mac)

        val generatedPackets = new LinkedList[GeneratedPacket]()

        val pkt = { eth src vm2Mac dst routerMac2 } <<
            { ip4 src vm2Ip.getAddress dst routerIp1.getAddress } <<
            { icmp.echo request }

        val (res, _) = simulate(packetContextFor(pkt, vm2Port.getId,
                                                 generatedPackets))
        res should be (NoOp)

        matchIcmp(generatedPackets, rtrPort2.getId, routerMac2, vm2Mac,
                  routerIp1.getAddress, vm2Ip.getAddress,
                  ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }

    scenario("Ping external ip address (vm1)") {
        feedArpTable(simRouter, vm1Ip.getAddress(), vm1Mac)

        val pkt = { eth src vm2Mac dst routerMac2 } <<
            { ip4 src vm2Ip.getAddress dst vm1Ip.getAddress } <<
            { icmp.echo request }

        val (res, pktCtx) = simulate(packetContextFor(pkt, vm2Port.getId))
        res should be (AddVirtualWildcardFlow)
        pktCtx.virtualFlowActions should have size 3

        // should come out the other side
        pktCtx.virtualFlowActions.get(2) should be (
            FlowActionOutputToVrnPort(rtrPort1.getId))
    }

    scenario("Ping reply from external to internal") {
        feedArpTable(simRouter, vm2Ip.getAddress(), vm2Mac)

        val pkt = { eth src vm1Mac dst routerMac1 } <<
            { ip4 src vm1Ip.getAddress dst vm2Ip.getAddress } <<
            { icmp.echo reply }

        val (res, pktCtx) = simulate(packetContextFor(pkt, rtrPort1.getId))
        res should be (AddVirtualWildcardFlow)
        pktCtx.virtualFlowActions should have size 3

        pktCtx.virtualFlowActions.get(2) should be (
            FlowActionOutputToVrnBridge(bridge, List(vm2Port.getId)))
    }

    /*
     * Sends a few pings all at once to make sure that the DDA picks them up
     * and and processes them correctly without overwriting the seqs as per
     * MN-273.
     */
    scenario("DDA preserves ping sequence numbers") {
        feedArpTable(simRouter, vm1Ip.getAddress(), vm1Mac)
        feedArpTable(simRouter, vm2Ip.getAddress(), vm2Mac)

        val icmpId: Short = 85.toShort
        val howMany = 20
        val makePacket = (x: Int) => {
            val frame = { eth src vm1Mac dst routerMac1 } <<
                { ip4 src vm1Ip.getAddress dst vm2Ip.getAddress } <<
                { icmp.echo.request.id(icmpId).seq(x.toShort) }
            new Packet(frame, FlowMatches.fromEthernetPacket(frame)
                           .addKey(FlowKeys.inPort(rtrPort1DpNum))
                           .setInputPortNumber(rtrPort1DpNum)) }
        val packets = (1 to howMany) map makePacket toArray

        packetWorkflow(Map(
            `rtrPort1DpNum` -> rtrPort1.getId,
            `vm2PortDpNum` -> vm2Port.getId)) ! PacketWorkflow.HandlePackets(packets)

        mockDpChannel.packetsSent should have size 20

        def icmp_quench(eth: Ethernet) = {
            val i = eth.getPayload.asInstanceOf[IPv4].getPayload.asInstanceOf[ICMP]
            (i.getIdentifier, i.getSequenceNum)
        }

        val seqs = mockDpChannel.packetsSent.map ( p => {
            icmp_quench(p.getEthernet) match {
                case (`icmpId`, seq: Short) => seq
                case _ => -1
            }
        }).filter(x => x > 0)
        seqs.sorted should be (1 to howMany)
    }
}
