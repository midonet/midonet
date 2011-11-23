/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.DHCP;
import com.midokura.midolman.packets.DHCPOption;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.UDP;

public class PacketHelper {

    static Random rand = new Random();
    MAC hwAddr;
    IntIPv4 ipAddr;

    public PacketHelper(MAC hwAddr, String ipv4) {
        this.hwAddr = hwAddr;
        this.ipAddr = IntIPv4.fromString(ipv4);
    }

    public byte[] makeDhcpDiscover() {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkDhcpOffer(byte[] request, byte[] reply, String ipAddr) {
        // TODO Auto-generated method stub

    }

    public byte[] makeDhcpRequest(byte[] reply) {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkDhcpAck(byte[] request, byte[] reply) {
        // TODO Auto-generated method stub

    }

    public byte[] makeArpRequest(IntIPv4 targetIp) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(hwAddr);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(ipAddr.address));
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(targetIp.address));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(hwAddr);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

    public void checkArpReply(byte[] bytes, MAC senderMac, IntIPv4 senderIp) {
        assertNotNull(bytes);
    	Ethernet pkt = new Ethernet();
        pkt.deserialize(bytes, 0, bytes.length);
        assertEquals(ARP.ETHERTYPE, pkt.getEtherType());
        assertEquals(senderMac, pkt.getSourceMACAddress());
        assertEquals(hwAddr, pkt.getDestinationMACAddress());
        ARP arp = ARP.class.cast(pkt.getPayload());
        assertEquals(ARP.HW_TYPE_ETHERNET, arp.getHardwareType());
        assertEquals(ARP.PROTO_TYPE_IP, arp.getProtocolType());
        assertEquals(6, arp.getHardwareAddressLength());
        assertEquals(4, arp.getProtocolAddressLength());
        assertEquals(ARP.OP_REPLY, arp.getOpCode());
        assertEquals(senderMac, arp.getSenderHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(senderIp.address),
                arp.getSenderProtocolAddress()));
        assertEquals(hwAddr, arp.getTargetHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(ipAddr.address),
                arp.getTargetProtocolAddress()));
    }

    public byte[] makeIcmpEchoRequest(MAC dstMac, IntIPv4 dstIp) {
        short id = (short) rand.nextInt();
        short seq = (short) rand.nextInt();
        byte[] data = new byte[17];
        rand.nextBytes(data);
        ICMP icmp = new ICMP();
        icmp.setEchoRequest(id, seq, data);
        IPv4 ip = new IPv4();
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setSourceAddress(ipAddr.address);
        ip.setDestinationAddress(dstIp.address);
        Ethernet pkt = new Ethernet();
        pkt.setPayload(ip);
        pkt.setEtherType(IPv4.ETHERTYPE);
        pkt.setSourceMACAddress(hwAddr);
        pkt.setDestinationMACAddress(dstMac);
        return pkt.serialize();
    }

    public void checkIcmpEchoReply(byte[] request, byte[] reply) {
        assertNotNull(request);
        assertNotNull(reply);
        Ethernet pktRequest = new Ethernet();
        pktRequest.deserialize(request, 0, request.length);
        Ethernet pktReply = new Ethernet();
        pktReply.deserialize(reply, 0, reply.length);
        assertEquals(pktRequest.getSourceMACAddress(),
                pktReply.getDestinationMACAddress());
        assertEquals(pktRequest.getDestinationMACAddress(),
                pktReply.getSourceMACAddress());
        assertEquals(IPv4.ETHERTYPE, pktRequest.getEtherType());
        assertEquals(IPv4.ETHERTYPE, pktReply.getEtherType());
        IPv4 ipRequest = IPv4.class.cast(pktRequest.getPayload());
        IPv4 ipReply = IPv4.class.cast(pktReply.getPayload());
        assertEquals(ipRequest.getDestinationAddress(),
                ipReply.getSourceAddress());
        assertEquals(ipRequest.getSourceAddress(),
                ipReply.getDestinationAddress());
        assertEquals(ICMP.PROTOCOL_NUMBER, ipRequest.getProtocol());
        assertEquals(ICMP.PROTOCOL_NUMBER, ipReply.getProtocol());
        ICMP icmpRequest = ICMP.class.cast(ipRequest.getPayload());
        ICMP icmpReply = ICMP.class.cast(ipReply.getPayload());
        assertEquals(icmpRequest.getIdentifier(), icmpReply.getIdentifier());
        assertEquals(icmpRequest.getSequenceNum(), icmpReply.getSequenceNum());
        assertTrue(Arrays.equals(icmpRequest.getData(), icmpReply.getData()));
        // TODO: the checksums should be different, but we should verify them.
        assertEquals(ICMP.CODE_NONE, icmpRequest.getCode());
        assertEquals(ICMP.CODE_NONE, icmpReply.getCode());
        assertEquals(ICMP.TYPE_ECHO_REQUEST, icmpRequest.getType());
        assertEquals(ICMP.TYPE_ECHO_REPLY, icmpReply.getType());
    }

    public void checkArpRequest(byte[] bytes, MAC senderMac, IntIPv4 senderIp) {
        Ethernet pkt = new Ethernet();
        pkt.deserialize(bytes, 0, bytes.length);
        assertEquals(ARP.ETHERTYPE, pkt.getEtherType());
        assertEquals(senderMac, pkt.getSourceMACAddress());
        assertEquals(MAC.fromString("ff:ff:ff:ff:ff:ff"),
                pkt.getDestinationMACAddress());
        ARP arp = ARP.class.cast(pkt.getPayload());
        assertEquals(ARP.HW_TYPE_ETHERNET, arp.getHardwareType());
        assertEquals(ARP.PROTO_TYPE_IP, arp.getProtocolType());
        assertEquals(6, arp.getHardwareAddressLength());
        assertEquals(4, arp.getProtocolAddressLength());
        assertEquals(ARP.OP_REQUEST, arp.getOpCode());
        assertEquals(senderMac, arp.getSenderHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(senderIp.address),
                arp.getSenderProtocolAddress()));
        assertEquals(MAC.fromString("00:00:00:00:00:00"),
                arp.getTargetHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(ipAddr.address),
                arp.getTargetProtocolAddress()));
    }

    public byte[] makeArpReply(MAC targetMac, IntIPv4 targetIp) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(hwAddr);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(ipAddr.address));
        arp.setTargetHardwareAddress(targetMac);
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(targetIp.address));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(hwAddr);
        pkt.setDestinationMACAddress(targetMac);
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

}
