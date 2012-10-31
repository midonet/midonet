/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util

import java.util.UUID

import com.midokura.packets._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.midolman.MidolmanTestCase
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.util.AddressConversions._

trait SimulationHelper extends MidolmanTestCase {

    final val IPv6_ETHERTYPE: Short = 0x86dd.toShort

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
              fromMac: MAC, fromIp: IntIPv4, fromPort: Short,
              toMac: MAC, toIp: IntIPv4, toPort: Short,
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
        dpProbe().expectMsgClass(classOf[PacketIn])

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
}
