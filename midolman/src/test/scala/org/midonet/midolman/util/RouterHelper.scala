/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.util

import java.util.UUID

import org.midonet.packets._
import org.midonet.cluster.client.RouterPort
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.MidolmanTestCase

trait RouterHelper extends SimulationHelper { this: MidolmanTestCase =>

    def expectEmitIcmp(fromMac: MAC, fromIp: IPv4Addr,
                               toMac: MAC, toIp: IPv4Addr,
                               icmpType: Char, icmpCode: Char) {
        val pkt = fishForRequestOfType[EmitGeneratedPacket](dedupProbe()).eth
        assertExpectedIcmpPacket(fromMac, fromIp, toMac, toIp, icmpType,
            icmpCode, pkt)
    }

    def assertExpectedIcmpPacket(fromMac: MAC, fromIp: IPv4Addr,
                                 toMac: MAC, toIp: IPv4Addr,
                                 icmpType: Char, icmpCode: Char,
                                 pkt: Ethernet){
        pkt.getEtherType should be (IPv4.ETHERTYPE)
        pkt.getSourceMACAddress should be (fromMac)
        pkt.getDestinationMACAddress should be (toMac)
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPkt.getDestinationAddress should be (toIp.addr)
        ipPkt.getSourceAddress should be (fromIp.addr)
        val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt.getType should be (icmpType)
        icmpPkt.getCode should be (icmpCode)
    }

    def expectEmitArpRequest(port: UUID, fromMac: MAC, fromIp: IPv4Addr,
                                     toIp: IPv4Addr) {
        val toMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val msg = fishForRequestOfType[EmitGeneratedPacket](dedupProbe())
        msg.egressPort should be (port)
        val eth = msg.eth
        eth.getSourceMACAddress should be (fromMac)
        eth.getDestinationMACAddress should be (toMac)
        eth.getEtherType should be (ARP.ETHERTYPE)
        eth.getPayload.getClass should be (classOf[ARP])
        val arp = eth.getPayload.asInstanceOf[ARP]
        arp.getHardwareAddressLength should be (6)
        arp.getProtocolAddressLength should be (4)
        arp.getSenderHardwareAddress should be (fromMac)
        arp.getTargetHardwareAddress should be (MAC.fromString("00:00:00:00:00:00"))
        IPv4Addr.fromBytes(arp.getSenderProtocolAddress) should be (fromIp)
        IPv4Addr.fromBytes(arp.getTargetProtocolAddress) should be (toIp)
        arp.getOpCode should be (ARP.OP_REQUEST)
    }

    def fetchRouterAndPort(portName: String,
                           portId: UUID) : (SimRouter, RouterPort) = {
        // Simulate a dummy packet so the system creates the Router RCU object
        val eth = new Ethernet().setEtherType(IPv6_ETHERTYPE).
            setDestinationMACAddress(MAC.fromString("de:de:de:de:de:de")).
            setSourceMACAddress(MAC.fromString("01:02:03:04:05:06")).
            setPad(true)
        triggerPacketIn(portName, eth)
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        val port = VirtualTopologyActor.everything(portId)
                        .asInstanceOf[RouterPort]
        val router = VirtualTopologyActor.everything(port.deviceID)
                        .asInstanceOf[SimRouter]
        drainProbes()
        (router, port)
    }
}
