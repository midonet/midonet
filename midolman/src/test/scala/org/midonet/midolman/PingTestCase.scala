/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable.{ListBuffer, HashMap}

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.util.RouterHelper
import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.packets._
import org.midonet.midolman.topology.LocalPortActive
import scala.Some
import org.midonet.midolman.guice.actors.OutgoingMessage
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class PingTestCase extends VirtualConfigurationBuilders with RouterHelper {
    private final val log = LoggerFactory.getLogger(classOf[PingTestCase])

    // Router port one connecting to host VM1
    val routerIp1 = new IPv4Subnet("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    // Interior router port connecting to bridge
    val routerIp2 = new IPv4Subnet("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    // VM1: remote host to ping
    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    val vm1Ip = new IPv4Subnet("192.168.111.2", 24)
    // DHCP client
    val vm2Ip = new IPv4Subnet("192.168.222.2", 24)
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")
    var brPort2 : BridgePort = null
    val vm2PortName = "VM2"
    var vm2PortNumber = 0
    var dhcpServerIp = 0
    var dhcpClientIp = 0
    var rtrPort1 : RouterPort = null
    val rtrPort1Name = "RouterPort1"
    var rtrPort1Num = 0

    override def beforeTest() {
        val host = newHost("myself", hostId())
        host should not be null

        val router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        expect[HostRequest] on vtpProbe()
        expect[OutgoingMessage] on vtpProbe()

        // set up materialized port on router
        rtrPort1 = newRouterPort(router, routerMac1,
            routerIp1.toUnicastString,
            routerIp1.toNetworkAddress.toString,
            routerIp1.getPrefixLen)
        rtrPort1 should not be null
        materializePort(rtrPort1, host, rtrPort1Name)
        val portEvent = expect[LocalPortActive] on portsProbe
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort1.getId)
        vifToLocalPortNumber(rtrPort1.getId) match {
            case Some(portNo : Short) => rtrPort1Num = portNo
            case None => fail("Not able to find data port number for Router port 1")
        }

        newRoute(router, "0.0.0.0", 0,
            routerIp1.toNetworkAddress.toString, routerIp1.getPrefixLen,
            NextHop.PORT, rtrPort1.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // set up logical port on router
        val rtrPort2 = newRouterPort(router, routerMac2,
            routerIp2.toUnicastString,
            routerIp2.toNetworkAddress.toString,
            routerIp2.getPrefixLen)
        rtrPort2 should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp2.toNetworkAddress.toString, routerIp2.getPrefixLen,
            NextHop.PORT, rtrPort2.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // create bridge link to router's logical port
        val bridge = newBridge("bridge")
        bridge should not be null

        val brPort1 = newBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        // add a materialized port on bridge, logically connect to VM2
        brPort2 = newBridgePort(bridge)
        brPort2 should not be null

        // DHCP related setup
        // set up Option 121
        var opt121Obj = (new Opt121()
                       .setGateway(routerIp2)
                       .setRtDstSubnet(routerIp1))
        var opt121Routes: List[Opt121] = List(opt121Obj)
        // set DHCP subnet
        var dhcpSubnet = (new Subnet()
                       .setSubnetAddr(routerIp2)
                       .setDefaultGateway(routerIp2)
                       .setOpt121Routes(opt121Routes))
        addDhcpSubnet(bridge, dhcpSubnet)
        // set DHCP host
        materializePort(brPort2, host, vm2PortName)
        expect[LocalPortActive] on portsProbe
        vifToLocalPortNumber(brPort2.getId) match {
            case Some(portNo : Short) => vm2PortNumber = portNo
            case None => fail("Not able to find data port number for bridge port 2")
        }
        var dhcpHost = (new org.midonet.cluster.data.dhcp.Host()
                           .setMAC(vm2Mac)
                           .setIp(new IntIPv4(vm2Ip)))
        addDhcpHost(bridge, dhcpSubnet, dhcpHost)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def expectPacketOut(portNum : Int): Ethernet = {
        val pktOut = getPacketOut
        log.debug("Packet execute: {}", pktOut)

        val flowActs = pktOut.getActions
        flowActs.size should be (1)

        val act = flowActs(0)
        act.getKey should be === FlowAction.FlowActionAttr.OUTPUT
        act.getValue.getClass() should be === classOf[FlowActionOutput]
        act.getValue.asInstanceOf[FlowActionOutput].getPortNumber should be (portNum)

        pktOut.getPacket
    }

    private def injectDhcpDiscover(portName: String, srcMac : MAC) {
        val dhcpDiscover = new DHCP()
        dhcpDiscover.setOpCode(0x01)
        dhcpDiscover.setHardwareType(0x01)
        dhcpDiscover.setHardwareAddressLength(6)
        dhcpDiscover.setClientHardwareAddress(srcMac)
        var options = ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.DISCOVER.value)))
        dhcpDiscover.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpDiscover)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    private def injectDhcpRequest(portName : String, srcMac : MAC) {
        val dhcpReq = new DHCP()
        dhcpReq.setOpCode(0x01)
        dhcpReq.setHardwareType(0x01)
        dhcpReq.setHardwareAddressLength(6)
        dhcpReq.setClientHardwareAddress(srcMac)
        dhcpReq.setServerIPAddress(dhcpServerIp)
        var options = ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.REQUEST.value)))
        options.add(new DHCPOption(DHCPOption.Code.REQUESTED_IP.value,
                           DHCPOption.Code.REQUESTED_IP.length,
                           Array[Byte](((dhcpClientIp >> 24) & 0xFF).toByte,
                                       ((dhcpClientIp >> 16) & 0xFF).toByte,
                                       ((dhcpClientIp >> 8) & 0xFF).toByte,
                                       dhcpClientIp.toByte)))
        options.add(new DHCPOption(DHCPOption.Code.SERVER_ID.value,
                           DHCPOption.Code.SERVER_ID.length,
                           Array[Byte](((dhcpServerIp >> 24) & 0xFF).toByte,
                                       ((dhcpServerIp >> 16) & 0xFF).toByte,
                                       ((dhcpServerIp >> 8) & 0xFF).toByte,
                                       dhcpServerIp.toByte)))
        dhcpReq.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpReq)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    private def expectEmitDhcpReply(expectedMsgType : Byte) = {
        val returnPkt = fishForRequestOfType[EmitGeneratedPacket](dedupProbe()).eth
        returnPkt.getEtherType should be === IPv4.ETHERTYPE
        val ipPkt = returnPkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be === UDP.PROTOCOL_NUMBER
        val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
        udpPkt.getSourcePort() should be === 67
        udpPkt.getDestinationPort() should be === 68
        val dhcpPkt = udpPkt.getPayload.asInstanceOf[DHCP]
        dhcpClientIp = dhcpPkt.getYourIPAddress
        dhcpServerIp = dhcpPkt.getServerIPAddress
        val replyOptions = HashMap[Byte, DHCPOption]()
        for (opt <- dhcpPkt.getOptions) {
            val code = opt.getCode
            replyOptions.put(code, opt)
            code match {
                case v if (v == DHCPOption.Code.DHCP_TYPE.value) =>
                    if (opt.getLength != 1) {
                        fail("DHCP option type value invalid length")
                    }
                    val msgType = opt.getData()(0)
                    msgType should be === expectedMsgType
                case _ => // Do nothing
            }
        }
    }

    /**
     * Sends an ICMP request and expects an ICMP reply back, it'll take care
     * of possible race conditions that might result in an intermediate ARP
     */
    private def doIcmpExchange(fromPort: String, srcMac: MAC, srcIp: IPv4Addr,
                       dstMac: MAC, dstIp: IPv4Addr) {

        drainProbes()

        injectIcmpEchoReq(fromPort, srcMac, srcIp, dstMac, dstIp)
        expectPacketIn()

        log.info("Check ICMP Echo Reply")

        val pkt = fishForRequestOfType[EmitGeneratedPacket](dedupProbe()).eth

        // Due to timing issues (how long it takes for ARP entry to be stored
        // on router side), we may get an ARP message before the ICMP message
        if (pkt.getEtherType == ARP.ETHERTYPE){
            log.info("Got an ARP, ignore")
            expectPacketOut(getPortNumber(fromPort))
            // Ignore this ARP packet and do a regular expect emit ICMP
            expectEmitIcmp(dstMac, dstIp, srcMac, srcIp,
                           ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
        } else {
            log.info("Got an ICMP reply straight away")
            // The packet we just found should be the expected ICMP packet
            assertExpectedIcmpPacket(dstMac, dstIp, srcMac, srcIp,
                                     ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE, pkt)
        }

        expectPacketOut(getPortNumber(fromPort))
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()
    }

    def test() {

        log.info("When the VM boots up, it should start sending DHCP discover")
        injectDhcpDiscover(vm2PortName, vm2Mac)
        expectPacketIn()

        log.info("Expecting MidoNet to respond with DHCP offer")
        // verify DHCP OFFER
        expectEmitDhcpReply(DHCPOption.MsgType.OFFER.value)
        expectPacketOut(vm2PortNumber)

        log.info("Got DHCPOFFER, broadcast DHCP Request")
        injectDhcpRequest(vm2PortName, vm2Mac)
        expectPacketIn()
        log.info("Expecting MidoNet to respond with DHCP Reply/Ack")
        // verify DHCP Reply
        expectEmitDhcpReply(DHCPOption.MsgType.ACK.value)
        expectPacketOut(vm2PortNumber)

        val vm2IpInt = vm2Ip.getAddress.addr
        dhcpClientIp should be === vm2IpInt

        feedArpCache(vm2PortName, vm2Ip.getAddress.addr, vm2Mac,
                     routerIp2.getAddress.addr, routerMac2)
        expectPacketIn()
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        log.info("Ping Router port 2")
        doIcmpExchange(vm2PortName, vm2Mac, vm2Ip.getAddress,
            routerMac2, routerIp2.getAddress)

        log.info("Ping Router port 1")
        doIcmpExchange(vm2PortName, vm2Mac, vm2Ip.getAddress,
            routerMac2, routerIp1.getAddress)

        log.info("Ping VM1, not expecting any reply")
        injectIcmpEchoReq(vm2PortName, vm2Mac, vm2Ip.getAddress,
            routerMac2, vm1Ip.getAddress)
        expectPacketIn()
        // This generated packet is an ARP request, the ICMP echo will not be
        // delivered because this ARP will go unanswered
        expect[EmitGeneratedPacket] on dedupProbe()
        expectPacketOut(rtrPort1Num)
        drainProbes()

        log.info("Send Ping reply on behalf of VM1")
        injectIcmpEchoReply(rtrPort1Name, vm1Mac, vm1Ip.getAddress, 16, 32,
                            routerMac1, vm2Ip.getAddress)
        expectPacketIn()

        log.info("Expecting packet out on VM2 port")
        val eth = expectRoutedPacketOut(vm2PortNumber)
        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (vm1Ip.getAddress.addr)
        ipPak.getDestinationAddress should be (vm2Ip.getAddress.addr)
        val icmpPak= ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be (ICMP.TYPE_ECHO_REPLY)
    }

    private def makeIcmpReqs(srcMac: MAC, srcIp: IPv4Addr,
                             dstMac: MAC, dstIp: IPv4Addr,
                             icmpId: Short, howMany: Short,
                             icmps: List[Ethernet] = List()): List[Ethernet] = {

        if (howMany == 0)
            return icmps

        val echo = new ICMP()
        echo.setEchoRequest(icmpId, howMany, "My ICMP".getBytes)

        val eth: Ethernet = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(dstMac)
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addr)
                                 .setDestinationAddress(dstIp.addr)
                                 .setProtocol(ICMP.PROTOCOL_NUMBER)
                                 .setPayload(echo))

        makeIcmpReqs(srcMac, srcIp, dstMac, dstIp, icmpId,
                     (howMany - 1).toShort, eth :: icmps)
    }
    /*
     * Sends a few pings all at once to make sure that the DDA picks them up
     * and and processes them correctly without overwriting the seqs as per
     * MN-273.
     */
    def testFlood() {

        def icmp_quench(eth: Ethernet) = {
            val i = eth.getPayload.asInstanceOf[IPv4].getPayload.asInstanceOf[ICMP]
            (i.getIdentifier, i.getSequenceNum)
        }

        // Let's feed the ARP cache to make sure there are no unexpected ARPs
        feedArpCache(vm2PortName, vm2Ip.getAddress.addr, vm2Mac,
                     routerIp2.getAddress.addr, routerMac2)
        expectPacketIn()
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        // Deliver 10 ICMPs at once to the DDA in the same batch, 9 should be
        // pended
        val icmpId: Short = 85.toShort
        val howMany: Short = 20
        triggerPacketsIn(rtrPort1Name,
                         makeIcmpReqs(vm1Mac, vm1Ip.getAddress, routerMac1,
                                      vm2Ip.getAddress, icmpId, howMany))

        // We should have a WF added for the FIRST packet
        ackWCAdded().getMatch.getIcmpIdentifier should be === icmpId

        // This gives time to the DDA to send the pended packets
        drainProbes()

        // Verify what all were sent, note that the ARPs do not get emitted
        mockDpConn().packetsSent should have size howMany
        val seqs = mockDpConn().packetsSent.map ( p => {
            icmp_quench(p.getPacket) match {
                case (`icmpId`, seq: Short) => seq
                case _ => -1
            }
        }).filter(x => x > 0)
        seqs.sorted should be === (1 to howMany)
    }
}
