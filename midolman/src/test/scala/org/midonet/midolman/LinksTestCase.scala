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

import akka.testkit.TestProbe
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.Router
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.RouterHelper
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.flows.FlowActions
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class LinksTestCase extends MidolmanTestCase
                    with RouterHelper {
    private final val log = LoggerFactory.getLogger(classOf[LinksTestCase])

    val rtrIp1 = new IPv4Subnet("192.168.111.1", 24)
    val rtrIp2 = new IPv4Subnet("192.168.222.1", 24)
    val vm1Ip = new IPv4Subnet("192.168.111.2", 24)
    val vm2Ip = new IPv4Subnet("192.168.222.2", 24)

    val rtrMac1 = MAC.fromString("aa:bb:cc:dd:11:11")
    val rtrMac2 = MAC.fromString("aa:bb:cc:dd:22:11")
    val vm1Mac = MAC.fromString("aa:bb:cc:dd:11:11")
    val vm2Mac = MAC.fromString("aa:bb:cc:dd:22:22")

    var rtrPort1 : RouterPort = null
    var rtrPort2 : RouterPort = null

    val rtrPort1Name = "RouterPort1"
    val rtrPort2Name = "RouterPort2"
    var rtrPort1Num = 0
    var rtrPort2Num = 0

    var router: Router = null
    var host: Host = null

    override def beforeTest() {
        host = newHost("myself", hostId())
        host should not be null

        router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        setupPorts()
        setupRoutes()

        // TODO remove, possibly
        val datapath = flowProbe().expectMsgType[DatapathReady].datapath
        datapath should not be null

        drainProbes()
    }


    private def buildRouterPort
            (ip: IPv4Subnet, mac: MAC, name: String): RouterPort = {
        val rtrPort = newRouterPort(router, mac,
            ip.toUnicastString,
            ip.toNetworkAddress.toString,
            ip.getPrefixLen)
        rtrPort should not be null
        materializePort(rtrPort, host, name)
        val portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort.getId)
        rtrPort
    }

    private def setupRoutes() {
        // 0.0.0.0/32 -> 192.168.111.0/24 via port 1
        val route1 = newRoute(router, "0.0.0.0", 0,
            rtrIp1.toNetworkAddress.toString, rtrIp1.getPrefixLen,
            NextHop.PORT, rtrPort1.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        route1 should not be null

        // 0.0.0.0/32 -> 192.168.222.0/24 via port 2
        val route2 = newRoute(router, "0.0.0.0", 0,
            rtrIp2.toNetworkAddress.toString, rtrIp2.getPrefixLen,
            NextHop.PORT, rtrPort2.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        route2 should not be null
    }

    private def setupPorts() {
        rtrPort1 = buildRouterPort(rtrIp1, rtrMac1, rtrPort1Name)
        vifToLocalPortNumber(rtrPort1.getId) match {
            case Some(portNo : Short) => rtrPort1Num = portNo
            case None => fail("Can't find data port number for Router port 1")
        }
        rtrPort2 = buildRouterPort(rtrIp2, rtrMac2, rtrPort2Name)
        vifToLocalPortNumber(rtrPort2.getId) match {
            case Some(portNo : Short) => rtrPort2Num = portNo
            case None => fail("Can't find data port number for Router port 2")
        }
    }

    def test() {
        // Feed ARP cache
        log.debug("Feeding ARP cache")
        feedArpCache(rtrPort1Name, vm1Ip.getAddress.addr, vm1Mac,
            rtrIp1.getAddress.addr, rtrMac1)
        requestOfType[PacketIn](packetInProbe)
        feedArpCache(rtrPort2Name, vm2Ip.getAddress.addr, vm2Mac,
            rtrIp2.getAddress.addr, rtrMac2)
        requestOfType[PacketIn](packetInProbe)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        log.debug("PING vm1 -> vm2")
        injectIcmpEchoReq(rtrPort1Name, vm1Mac, vm1Ip.getAddress,
            rtrMac1, vm2Ip.getAddress)
        requestOfType[PacketIn](packetInProbe)
        var pkt =
            expectRoutedPacketOut(rtrPort2Num).getPayload.asInstanceOf[IPv4]
        pkt.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        pkt.getSourceAddress should be (vm1Ip.getAddress.addr)
        pkt.getDestinationAddress should be (vm2Ip.getAddress.addr)

        log.debug("PING vm1 -> vm2")
        injectIcmpEchoReq(rtrPort2Name, vm2Mac, vm2Ip.getAddress,
            rtrMac2, vm1Ip.getAddress)
        requestOfType[PacketIn](packetInProbe)
        pkt = expectRoutedPacketOut(rtrPort1Num).getPayload.asInstanceOf[IPv4]
        pkt.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        pkt.getSourceAddress should be (vm2Ip.getAddress.addr)
        pkt.getDestinationAddress should be (vm1Ip.getAddress.addr)

        log.debug("Deactivate rtrPort2");
        val port2Ifc = new InterfaceDescription(rtrPort2Name)
        port2Ifc.setHasLink(false)
        port2Ifc.setUp(false)
        interfaceScanner.addInterface(port2Ifc)

        var portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(false)
        portEvent.portID should be(rtrPort2.getId)

        log.debug("PING vm1 -> vm2, route is dead")
        // wait for the routes to be updated
        fishForRequestOfType[simulation.Router](vtaProbe())
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)

        drainProbes()
        injectIcmpEchoReq(rtrPort1Name, vm1Mac, vm1Ip.getAddress,
            rtrMac1, vm2Ip.getAddress)
        requestOfType[PacketIn](packetInProbe)

        // can't use expectPacketOut because we inspect actions differently
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe)
        pktOut.packet should not be null
        pktOut.packet.getData should not be null
        pktOut.actions should contain(FlowActions.output(rtrPort1Num))

        pkt = Ethernet.deserialize(pktOut.packet.getData)
                      .getPayload.asInstanceOf[IPv4]

        pkt.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        pkt.getSourceAddress should be (rtrIp1.getAddress.addr)
        pkt.getDestinationAddress should be (vm1Ip.getAddress.addr)

        val icmp = pkt.getPayload.asInstanceOf[ICMP]
        icmp should not be null
        icmp.getType should be (ICMP.TYPE_UNREACH)

        drainProbes()

        log.debug("Reactivate port2")
        port2Ifc.setHasLink(true)
        port2Ifc.setUp(true)
        interfaceScanner.addInterface(port2Ifc)
        portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort2.getId)
        log.debug("Port2 is now active")

        // NOTE: at this point if we do a ping vm1 -> vm2 we are racing with
        // the 192.168.222.* routes being restored on the route table. Apart of
        // doing a sleep, the only option we have is to listen when the routes
        // are updated. This can be done by detecting RCU Routers being sent
        // from the RouterManager to the VTA. It does it twice because routes
        // get added one by one in the RouterManager.
        fishForRequestOfType[simulation.Router](vtaProbe())
        fishForRequestOfType[simulation.Router](vtaProbe())
        drainProbes()

        log.info("PING vm1 -> vm2, should pass now")
        injectIcmpEchoReq(rtrPort1Name, vm1Mac, vm1Ip.getAddress,
            rtrMac1, vm2Ip.getAddress)
        pkt = expectRoutedPacketOut(rtrPort2Num).getPayload.asInstanceOf[IPv4]
        pkt.getSourceAddress should be (vm1Ip.getAddress.addr)
        pkt.getDestinationAddress should be (vm2Ip.getAddress.addr)

    }

}
