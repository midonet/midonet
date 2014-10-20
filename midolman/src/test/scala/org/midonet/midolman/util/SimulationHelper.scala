/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.util

import java.util.{List => JList}
import java.util.UUID
import scala.collection.JavaConversions._

import org.slf4j.LoggerFactory

import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._
import org.midonet.odp.Packet
import org.midonet.odp.flows._
import org.midonet.odp.flows.FlowActions.output
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

trait SimulationHelper { this: MidolmanTestCase =>

    private final val log = LoggerFactory.getLogger(classOf[SimulationHelper])

    final val IPv6_ETHERTYPE: Short = 0x86dd.toShort

    def applyOutPacketActions(packetEx: PacketsExecute): Ethernet = {
        val packet = packetEx.packet
        val actions = packetEx.actions
        packet should not be null
        packet.getEthernet should not be null
        actions should not be null

        val eth = Ethernet.deserialize(packet.getEthernet.serialize())
        var ip: IPv4 = null
        var tcp: TCP = null
        var udp: UDP = null
        var icmp: ICMP = null
        log.debug("Applying out packet actions on {}", eth)
        eth.getEtherType match {
            case IPv4.ETHERTYPE =>
                ip = eth.getPayload.asInstanceOf[IPv4]
                ip.getProtocol match {
                    case TCP.PROTOCOL_NUMBER =>
                        tcp = ip.getPayload.asInstanceOf[TCP]
                    case UDP.PROTOCOL_NUMBER =>
                        udp = ip.getPayload.asInstanceOf[UDP]
                    case ICMP.PROTOCOL_NUMBER =>
                        icmp = ip.getPayload.asInstanceOf[ICMP]
                }
        }

        val actionsSet = actions.flatMap(action => action match {
            case a: FlowActionSetKey => Option(a)
            case _ => None
        }).toSet

        // TODO(guillermo) incomplete, but it should cover testing needs
        actionsSet foreach { action =>
            action.getFlowKey match {
                case key: FlowKeyEthernet =>
                    if (key.getDst != null) eth.setDestinationMACAddress(key.getDst)
                    if (key.getSrc != null) eth.setSourceMACAddress(key.getSrc)
                case key: FlowKeyIPv4 =>
                    ip should not be null
                    if (key.getDst != 0) ip.setDestinationAddress(key.getDst)
                    if (key.getSrc != 0) ip.setSourceAddress(key.getSrc)
                    if (key.getTtl != 0) ip.setTtl(key.getTtl)
                case key: FlowKeyTCP =>
                    tcp should not be null
                    if (key.getDst != 0) tcp.setDestinationPort(key.getDst)
                    if (key.getSrc != 0) tcp.setSourcePort(key.getSrc)
                case key: FlowKeyUDP =>
                    udp should not be null
                    if (key.getUdpDst != 0) udp.setDestinationPort(key.getUdpDst)
                    if (key.getUdpSrc != 0) udp.setSourcePort(key.getUdpSrc)
                case key: FlowKeyICMPError =>
                    log.debug("FlowKeyIcmpError related actions are userspace" +
                        "only so they should have been applied already in" +
                        "FlowController.applyActionsAfterUserspaceMatch")
                    icmp.getData should be (key.getIcmpData)
                case unmatched =>
                    log.warn("Won't translate {}", unmatched)
            }
        }

        eth
    }

    def getPacketOut: PacketsExecute =
        checkPacket((expect[PacketsExecute] on packetsEventsProbe))

    def checkPacket(p: PacketsExecute): PacketsExecute = {
        p.packet should not be null
        p.packet.getEthernet should not be null
        p
    }

    def getOutPacketPorts(packet: PacketsExecute): Set[Short] =
        actionsToOutputPorts(checkPacket(packet).actions)

    def actionsToOutputPorts(actions: JList[FlowAction]): Set[Short] = {
        actions should not be null
        actions.withFilter( _.isInstanceOf[FlowActionOutput] )
            .map{ _.asInstanceOf[FlowActionOutput].getPortNumber.toShort }
            .toSet
    }

    def injectArpRequest(portName: String, srcIp: Int, srcMac: MAC, dstIp: Int) {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REQUEST)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(srcIp)
        arp.setTargetHardwareAddress("ff:ff:ff:ff:ff:ff")
        arp.setTargetProtocolAddress(dstIp)

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress("ff:ff:ff:ff:ff:ff")
        eth.setEtherType(ARP.ETHERTYPE)
        triggerPacketIn(portName, eth)
    }

    def feedArpCache(portName: String, srcIp: Int, srcMac: MAC,
                     dstIp: Int, dstMac: MAC) {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(srcIp)
        arp.setTargetHardwareAddress(dstMac)
        arp.setTargetProtocolAddress(dstIp)

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(dstMac)
        eth.setEtherType(ARP.ETHERTYPE)

        triggerPacketIn(portName, eth)
    }

    def injectTcp(port: String,
              fromMac: MAC, fromIp: IPv4Addr, fromPort: Int,
              toMac: MAC, toIp: IPv4Addr, toPort: Int,
              syn: Boolean = false, rst: Boolean = false, ack: Boolean = false,
              fragmentType: IPFragmentType = IPFragmentType.None) {
        val tcp = new TCP()
        tcp.setSourcePort(fromPort)
        tcp.setDestinationPort(toPort)
        val flags = 0 | (if (syn) 0x00 else 0x02) |
                        (if (rst) 0x00 else 0x04) |
                        (if (ack) 0x00 else 0x10)
        tcp.setFlags(flags.toShort)
        tcp.setPayload(new Data("TCP Payload".getBytes))

        val ipFlags = if (fragmentType == IPFragmentType.None) 0 else IPv4.IP_FLAGS_MF
        val offset = if (fragmentType == IPFragmentType.Later) 0x4321 else 0
        val ip = new IPv4().setSourceAddress(fromIp.addr).
                            setDestinationAddress(toIp.addr).
                            setProtocol(TCP.PROTOCOL_NUMBER).
                            setFlags(ipFlags.toByte).
                            setFragmentOffset(offset.toShort).
                            setTtl(64).
                            setPayload(tcp)
        val eth = new Ethernet().setSourceMACAddress(fromMac).
                                 setDestinationMACAddress(toMac).
                                 setEtherType(IPv4.ETHERTYPE).
                                 setPayload(ip).asInstanceOf[Ethernet]
        triggerPacketIn(port, eth)
    }

    def expectPacketOnPort(port: UUID): PacketIn = {
        val pktInMsg = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.eth should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.inputPort should be (port)
        pktInMsg
    }

    def fishForFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f
    }

    def expectFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = requestOfType[WildcardFlowAdded](wflowAddedProbe)
        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f
    }

    def expectMatchForIPv4Packet(pkt: Ethernet, wmatch: WildcardMatch) {
        wmatch.getEthDst should be (pkt.getDestinationMACAddress)
        wmatch.getEthSrc should be (pkt.getSourceMACAddress)
        wmatch.getEtherType should be (pkt.getEtherType)
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        wmatch.getNetworkDestinationIP should be (ipPkt.getDestinationIPAddress)
        wmatch.getNetworkSourceIP should be (ipPkt.getSourceIPAddress)
        wmatch.getNetworkProto should be (ipPkt.getProtocol)

        ipPkt.getProtocol match {
            case UDP.PROTOCOL_NUMBER =>
                val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
                wmatch.getDstPort should be (udpPkt.getDestinationPort)
                wmatch.getSrcPort should be (udpPkt.getSourcePort)
            case TCP.PROTOCOL_NUMBER =>
                val tcpPkt = ipPkt.getPayload.asInstanceOf[TCP]
                wmatch.getDstPort should be (tcpPkt.getDestinationPort)
                wmatch.getSrcPort should be (tcpPkt.getSourcePort)
            case _ =>
        }
    }

    def localPortNumberToName(portNo: Short): Option[String] = {
        dpController().underlyingActor.dpState.getDpPortName(
            Unsigned.unsign(portNo))
    }

    def injectIcmpEchoReq(portName : String, srcMac : MAC, srcIp : IPv4Addr,
                       dstMac : MAC, dstIp : IPv4Addr, icmpId: Short = 16,
                       icmpSeq: Short = 32) : ICMP =  {
        val echo = new ICMP()
        echo.setEchoRequest(icmpId, icmpSeq, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(srcMac).
            setDestinationMACAddress(dstMac).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addr).
            setDestinationAddress(dstIp.addr).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        triggerPacketIn(portName, eth)
        echo
    }

    def injectIcmpEchoReply(portName : String, srcMac : MAC, srcIp : IPv4Addr,
                            echoId : Short, echoSeqNum : Short, dstMac : MAC,
                            dstIp : IPv4Addr) = {
        val echoReply = new ICMP()
        echoReply.setEchoReply(echoId, echoSeqNum, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(srcMac).
            setDestinationMACAddress(dstMac).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addr).
            setDestinationAddress(dstIp.addr).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echoReply))
        triggerPacketIn(portName, eth)
        echoReply
    }

    def injectIcmpUnreachable(portName: String, srcMac: MAC, dstMac: MAC,
                    code: ICMP.UNREACH_CODE, origEth: Ethernet) {

        val origIpPkt = origEth.getPayload.asInstanceOf[IPv4]

        val icmp = new ICMP()
        icmp.setUnreachable(code, origIpPkt)

        val ip = new IPv4()
        ip.setSourceAddress(origIpPkt.getDestinationAddress)
        ip.setDestinationAddress(origIpPkt.getSourceAddress)
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)

        val eth: Ethernet = new Ethernet()
            .setSourceMACAddress(srcMac)
            .setDestinationMACAddress(dstMac)
            .setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)

        triggerPacketIn(portName, eth)
    }

    def expectRoutedPacketOut(portNum : Int): Ethernet = {
        val pktOut = getPacketOut
        val flowActs = pktOut.actions
        flowActs.size should equal (3)
        flowActs.contains(output(portNum)) should be (true)
        pktOut.packet.getEthernet
    }

    /**
     * Expects a packet on all the given ports, listening on MidolmanTestCase
     * events probe. The vlan ids provided in the vlanIdsPush and vlanIdsPop
     * params will be used to compare against the actions. Since the packets
     * won't have the actions applied, we will check here if the packet's
     * actions contain those for pushing or popping those vlans.
     */
    def expectPacketOutWithVlanIds(portNums : Seq[Int],
            vlanIdsPush: List[Short], vlanIdsPop: List[Short]): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe)
        pktOut.packet should not be null
        pktOut.packet.getEthernet should not be null
        val actions = pktOut.actions
        actions.size should be (portNums.size +
                                            vlanIdsPush.size + vlanIdsPop.size)

        // Check that we're outputting on the right ports
        portNums.foreach( portNum =>
            actions.filter {
                x => x.isInstanceOf[FlowActionOutput]}.toList map { action =>
                    action.asInstanceOf[FlowActionOutput].getPortNumber
                } should contain (portNum)
        )

        // Check that the vlan ids to push contained in the actions are those
        // that were expected
        vlanIdsPush.foreach( vlanId =>
            actions.filter {
                x => x.isInstanceOf[FlowActionPushVLAN]}.toList map { action =>
                    // The VlanId is just 12 bits in that field
                    (action.asInstanceOf[FlowActionPushVLAN]
                           .getTagControlIdentifier & 0x0fff).toShort
                } should contain (vlanId)
        )

        // Pop actions don't have the vlan id, but check that we have the right
        // number at least (this could be improved by verifying the actual
        // ids in the frame)
        vlanIdsPop.size shouldBe actions.count( _.isInstanceOf[FlowActionPopVLAN])

        pktOut.packet.getEthernet
    }

}
