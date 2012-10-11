/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util

import com.midokura.packets._
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import java.util.UUID
import com.midokura.midolman.simulation.Router
import com.midokura.midonet.cluster.client.RouterPort
import com.midokura.midolman.topology.VirtualTopologyActor.{RouterRequest, PortRequest}
import com.midokura.midolman.guice.actors.OutgoingMessage
import com.midokura.midolman.simulation.{ArpTableImpl, Router => SimRouter}
import org.scalatest.matchers.ShouldMatchers
import akka.testkit.TestKit
import com.midokura.midolman.MidolmanTestCase

trait RouterHelper extends MidolmanTestCase {

    final val IPv6_ETHERTYPE: Short = 0x86dd.toShort

    def feedArpCache(portName: String, srcIp: Int, srcMac: MAC,
                             dstIp: Int, dstMac: MAC) {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(srcIp))
        arp.setTargetHardwareAddress(dstMac)
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(dstIp))

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(dstMac)
        eth.setEtherType(ARP.ETHERTYPE)
        triggerPacketIn(portName, eth)
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

    def expectEmitIcmp(fromMac: MAC, fromIp: IntIPv4,
                               toMac: MAC, toIp: IntIPv4,
                               icmpType: Char, icmpCode: Char) {
        val errorPkt = requestOfType[EmitGeneratedPacket](simProbe()).ethPkt
        errorPkt.getEtherType should be === IPv4.ETHERTYPE
        val ipErrorPkt = errorPkt.getPayload.asInstanceOf[IPv4]
        ipErrorPkt.getProtocol should be === ICMP.PROTOCOL_NUMBER
        ipErrorPkt.getDestinationAddress should be === toIp.addressAsInt
        ipErrorPkt.getSourceAddress should be === fromIp.addressAsInt
        val icmpPkt = ipErrorPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt.getType should be === icmpType
        icmpPkt.getCode should be === icmpCode
    }

    def expectEmitArpRequest(port: UUID, fromMac: MAC, fromIp: IntIPv4,
                                     toIp: IntIPv4) {
        val toMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val msg = requestOfType[EmitGeneratedPacket](simProbe())
        msg.egressPort should be === port
        val eth = msg.ethPkt
        eth.getSourceMACAddress should be === fromMac
        eth.getDestinationMACAddress should be === toMac
        eth.getEtherType should be === ARP.ETHERTYPE
        eth.getPayload.getClass should be === classOf[ARP]
        val arp = eth.getPayload.asInstanceOf[ARP]
        arp.getHardwareAddressLength should be === 6
        arp.getProtocolAddressLength should be === 4
        arp.getSenderHardwareAddress should be === fromMac
        arp.getTargetHardwareAddress should be === MAC.fromString("00:00:00:00:00:00")
        new IntIPv4(arp.getSenderProtocolAddress) should be === fromIp
        new IntIPv4(arp.getTargetProtocolAddress) should be === toIp
        arp.getOpCode should be === ARP.OP_REQUEST
    }

    def fetchRouterAndPort(portName: String) : (SimRouter, RouterPort[_]) = {
        // Simulate a dummy packet so the system creates the Router RCU object
        val eth = (new Ethernet()).setEtherType(IPv6_ETHERTYPE).
            setDestinationMACAddress(MAC.fromString("de:de:de:de:de:de")).
            setSourceMACAddress(MAC.fromString("01:02:03:04:05:06")).
            setPad(true)
        triggerPacketIn(portName, eth)

        requestOfType[PortRequest](vtaProbe())
        val port = requestOfType[OutgoingMessage](vtaProbe()).m.asInstanceOf[RouterPort[_]]
        requestOfType[RouterRequest](vtaProbe())
        val router = replyOfType[SimRouter](vtaProbe())
        drainProbes()
        (router, port)
    }

}
