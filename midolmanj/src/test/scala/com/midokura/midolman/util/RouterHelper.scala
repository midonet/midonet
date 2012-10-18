/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.util

import java.util.UUID

import com.midokura.packets._
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midonet.cluster.client.RouterPort
import com.midokura.midolman.topology.VirtualTopologyActor.{RouterRequest, PortRequest}
import com.midokura.midolman.simulation.{Router => SimRouter}

trait RouterHelper extends SimulationHelper {

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

        fishForRequestOfType[PortRequest](vtaProbe())
        val port = fishForReplyOfType[RouterPort[_]](vtaProbe())
        fishForRequestOfType[RouterRequest](vtaProbe())
        val router = fishForReplyOfType[SimRouter](vtaProbe())
        drainProbes()
        (router, port)
    }

}
