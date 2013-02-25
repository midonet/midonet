/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.util

import java.util.UUID
import java.util.{List => JList}

import scala.collection.JavaConversions._

import org.midonet.midolman.DatapathController.PacketIn
import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.MidolmanTestCase
import org.midonet.odp.Packet
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import akka.testkit.TestProbe
import org.slf4j.LoggerFactory

trait SimulationHelper extends MidolmanTestCase {

    private final val log = LoggerFactory.getLogger(classOf[SimulationHelper])

    final val IPv6_ETHERTYPE: Short = 0x86dd.toShort

    def applyOutPacketActions(packet: Packet): Ethernet = {
        packet should not be null
        packet.getData should not be null
        packet.getActions should not be null

        val eth = Ethernet.deserialize(packet.getData)
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

        val actions = packet.getActions.flatMap(action => action match {
            case a: FlowActionSetKey => Option(a)
            case _ => None
        }).toSet

        // TODO(guillermo) incomplete, but it should cover testing needs
        actions foreach { action =>
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
                    icmp.getData should be === key.getIcmpData
                case unmatched =>
                    log.warn("Won't translate {}", unmatched)
            }
        }

        eth
    }

    private def actionsToOutputPorts(actions: JList[FlowAction[_]]): Set[Short]
    = {
        actions.flatMap(action => action match {
            case a: FlowActionOutput => Option(a.getValue.getPortNumber.toShort)
            case _ => None
        }).toSet
    }

    def getFlowOutputPorts(flow: WildcardFlow): Set[Short] = {
        actionsToOutputPorts(flow.getActions)
    }


    def getOutPacketPorts(packet: Packet): Set[Short] = {
        packet should not be null
        packet.getData should not be null
        packet.getActions should not be null
        actionsToOutputPorts(packet.getActions)
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
              fromMac: MAC, fromIp: IntIPv4, fromPort: Int,
              toMac: MAC, toIp: IntIPv4, toPort: Int,
              syn: Boolean = false, rst: Boolean = false, ack: Boolean = false) {
        val tcp = new TCP()
        tcp.setSourcePort(fromPort)
        tcp.setDestinationPort(toPort)
        val flags = 0 | (if (syn) 0x00 else 0x02) |
                        (if (rst) 0x00 else 0x04) |
                        (if (ack) 0x00 else 0x10)
        tcp.setFlags(flags.toShort)
        tcp.setPayload(new Data("TCP Payload".getBytes))
        val ip = new IPv4().setSourceAddress(fromIp.addressAsInt).
                            setDestinationAddress(toIp.addressAsInt).
                            setProtocol(TCP.PROTOCOL_NUMBER).
                            setTtl(64).
                            setPayload(tcp)
        val eth = new Ethernet().setSourceMACAddress(fromMac).
                                 setDestinationMACAddress(toMac).
                                 setEtherType(IPv4.ETHERTYPE).
                                 setPayload(ip).asInstanceOf[Ethernet]
        triggerPacketIn(port, eth)
    }

    def expectPacketOnPort(port: UUID): PacketIn = {
        fishForRequestOfType[PacketIn](dpProbe())

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be === port
        pktInMsg
    }

    def fishForFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = fishForRequestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow
    }

    def expectFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow
    }

    def expectMatchForIPv4Packet(pkt: Ethernet, wmatch: WildcardMatch) {
        wmatch.getEthernetDestination should be === pkt.getDestinationMACAddress
        wmatch.getEthernetSource should be === pkt.getSourceMACAddress
        wmatch.getEtherType should be === pkt.getEtherType
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        wmatch.getNetworkDestination should be === ipPkt.getDestinationAddress
        wmatch.getNetworkSource should be === ipPkt.getSourceAddress
        wmatch.getNetworkProtocol should be === ipPkt.getProtocol

        ipPkt.getProtocol match {
            case UDP.PROTOCOL_NUMBER =>
                val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
                wmatch.getTransportDestination should be === udpPkt.getDestinationPort
                wmatch.getTransportSource should be === udpPkt.getSourcePort
            case TCP.PROTOCOL_NUMBER =>
                val tcpPkt = ipPkt.getPayload.asInstanceOf[TCP]
                wmatch.getTransportDestination should be === tcpPkt.getDestinationPort
                wmatch.getTransportSource should be === tcpPkt.getSourcePort
            case _ =>
        }
    }

    def localPortNumberToName(portNo: Short): Option[String] = {
        dpController().underlyingActor.vportMgr.getDpPortName(
            Unsigned.unsign(portNo))
    }

    def injectIcmpEchoReq(portName : String, srcMac : MAC, srcIp : IntIPv4,
                       dstMac : MAC, dstIp : IntIPv4, icmpId: Short = 16,
                       icmpSeq: Short = 32) : ICMP =  {
        val echo = new ICMP()
        echo.setEchoRequest(icmpId, icmpSeq, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(srcMac).
            setDestinationMACAddress(dstMac).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addressAsInt).
            setDestinationAddress(dstIp.addressAsInt).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        triggerPacketIn(portName, eth)
        echo
    }

    def injectIcmpEchoReply(portName : String, srcMac : MAC, srcIp : IntIPv4,
                            echoId : Short, echoSeqNum : Short, dstMac : MAC,
                            dstIp : IntIPv4) = {
        val echoReply = new ICMP()
        echoReply.setEchoReply(echoId, echoSeqNum, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(srcMac).
            setDestinationMACAddress(dstMac).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(srcIp.addressAsInt).
            setDestinationAddress(dstIp.addressAsInt).
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
        icmp
    }

    def expectRoutedPacketOut(portNum : Int,
                              packetEventsProbe: TestProbe): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        val flowActs = pktOut.getActions
        flowActs.size should equal (3)
        flowActs.contains(FlowActions.output(portNum)) should be (true)
        Ethernet.deserialize(pktOut.getData)
    }

    def expectPacketOut(portNum : Int,
                        packetEventsProbe: TestProbe): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        pktOut.getActions.size should equal (1)
        pktOut.getActions.toList map { action =>
            action.getKey should be === FlowAction.FlowActionAttr.OUTPUT
            action.getValue.getClass should be === classOf[FlowActionOutput]
            action.getValue.asInstanceOf[FlowActionOutput].getPortNumber
        } should contain (portNum)
        Ethernet.deserialize(pktOut.getData)
    }

}
