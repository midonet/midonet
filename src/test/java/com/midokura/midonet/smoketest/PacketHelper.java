/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.Assert;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.ICMP.UNREACH_CODE;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;

public class PacketHelper {

    static Random rand = new Random();
    MAC epMac; // simulated endpoint's mac
    IntIPv4 epIp; // simulated endpoint's ip
    MAC gwMac;
    IntIPv4 gwIp;

    public PacketHelper(MAC mac, IntIPv4 ip, MAC gwMac, IntIPv4 gwIp) {
        this.epMac = mac;
        this.epIp = ip;
        this.gwMac = gwMac;
        this.gwIp = gwIp;
    }

    /**
     * Make an ARP reply from the endpoint to the Gateway.
     * @return
     */
    public byte[] makeArpReply() {
        return makeArpReply(epMac, epIp, gwMac, gwIp);
    }

    public static byte[] makeArpReply(MAC dlSrc, IntIPv4 nwSrc, MAC dlDst,
            IntIPv4 nwDst) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.address));
        arp.setTargetHardwareAddress(dlDst);
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwDst.address));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(dlDst);
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

    /**
     * Check that the packet is an ARP reply from the gateway to the endpoint.
     * @param recv
     */
    public void checkArpReply(byte[] recv) {
        checkArpReply(recv, gwMac, gwIp, epMac, epIp);
    }

    public static void checkArpReply(byte[] recv, MAC dlSrc, IntIPv4 nwSrc,
            MAC dlDst, IntIPv4 nwDst) {
        assertNotNull(recv);
        Ethernet pkt = new Ethernet();
        pkt.deserialize(recv, 0, recv.length);
        assertEquals(ARP.ETHERTYPE, pkt.getEtherType());
        assertEquals(dlSrc, pkt.getSourceMACAddress());
        assertEquals(dlDst, pkt.getDestinationMACAddress());
        ARP arp = ARP.class.cast(pkt.getPayload());
        assertEquals(ARP.HW_TYPE_ETHERNET, arp.getHardwareType());
        assertEquals(ARP.PROTO_TYPE_IP, arp.getProtocolType());
        assertEquals(6, arp.getHardwareAddressLength());
        assertEquals(4, arp.getProtocolAddressLength());
        assertEquals(ARP.OP_REPLY, arp.getOpCode());
        assertEquals(dlSrc, arp.getSenderHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(nwSrc.address),
                arp.getSenderProtocolAddress()));
        assertEquals(dlDst, arp.getTargetHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(nwDst.address),
                arp.getTargetProtocolAddress()));
    }

    /**
     * Make an ARP request to resolve the resolve the Gateway's ip.
     * @return
     */
    public byte[] makeArpRequest() {
        return makeArpRequest(epMac, epIp, gwIp);
    }

    public static byte[] makeArpRequest(MAC dlSrc, IntIPv4 nwSrc, IntIPv4 nwDst) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.address));
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwDst.address));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

    /**
     * Check that the packet is an ARP request from the gateway to the endpoint.
     * @param recv
     */
    public void checkArpRequest(byte[] recv) {
        checkArpRequest(recv, gwMac, gwIp, epIp);
    }

    public static void checkArpRequest(byte[] recv, MAC dlSrc, IntIPv4 nwSrc,
            IntIPv4 nwDst) {

        assertNotNull(recv);

        Ethernet pkt = new Ethernet();
        pkt.deserialize(recv, 0, recv.length);

        assertEquals(ARP.ETHERTYPE, pkt.getEtherType());
        assertEquals(dlSrc, pkt.getSourceMACAddress());
        assertEquals(MAC.fromString("ff:ff:ff:ff:ff:ff"),
                pkt.getDestinationMACAddress());
        ARP arp = ARP.class.cast(pkt.getPayload());
        assertEquals(ARP.HW_TYPE_ETHERNET, arp.getHardwareType());
        assertEquals(ARP.PROTO_TYPE_IP, arp.getProtocolType());
        assertEquals(6, arp.getHardwareAddressLength());
        assertEquals(4, arp.getProtocolAddressLength());
        assertEquals(ARP.OP_REQUEST, arp.getOpCode());
        assertEquals(dlSrc, arp.getSenderHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(nwSrc.address),
                arp.getSenderProtocolAddress()));
        assertEquals(MAC.fromString("00:00:00:00:00:00"),
                arp.getTargetHardwareAddress());
        assertTrue(Arrays.equals(IPv4.toIPv4AddressBytes(nwDst.address),
                arp.getTargetProtocolAddress()));
    }

    /**
     * Check that the ICMP echo reply matches the request.
     * @param request
     * @param reply
     */
    public static void checkIcmpEchoReply(byte[] request, byte[] reply) {
        checkIcmpEchoReply(request, reply, null);
    }

    /**
     * Check that the ICMP echo reply matches the packet except it has the
     * specified src L3 address.
     * @param request
     * @param reply
     * @param srcIp
     */
    public static void checkIcmpEchoReply(byte[] request, byte[] reply,
            IntIPv4 srcIp) {
        assertNotNull(request);
        assertNotNull(reply);
        assertEquals(request.length, reply.length);
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
        assertEquals(ipRequest.getSourceAddress(),
                ipReply.getDestinationAddress());
        if (null == srcIp)
            assertEquals(ipRequest.getDestinationAddress(),
                    ipReply.getSourceAddress());
        else
            assertEquals(srcIp.address,
                    ipReply.getSourceAddress());
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

    /**
     * Make an ICMP request from the endpoint to the specified destination.
     * Use the gw's mac as the L2 destination.
     * @param dstIp
     * @return
     */
    public byte[] makeIcmpEchoRequest(IntIPv4 dstIp) {
        return makeIcmpEchoRequest(epMac, epIp, gwMac, dstIp);
    }

    public static byte[] makeIcmpEchoRequest(MAC dlSrc, IntIPv4 nwSrc, MAC dlDst,
            IntIPv4 nwDst) {
        short id = (short) rand.nextInt();
        short seq = (short) rand.nextInt();
        byte[] data = new byte[17];
        rand.nextBytes(data);
        ICMP icmp = new ICMP();
        icmp.setEchoRequest(id, seq, data);
        IPv4 ip = new IPv4();
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setSourceAddress(nwSrc.address);
        ip.setDestinationAddress(nwDst.address);
        Ethernet pkt = new Ethernet();
        pkt.setPayload(ip);
        pkt.setEtherType(IPv4.ETHERTYPE);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(dlDst);
        return pkt.serialize();
    }

    /**
     * Check that the received ICMP echo request matches what was sent from
     * a peer, except the L2 addresses.
     * @param sent
     * @param recv
     */
    public void checkIcmpEchoRequest(byte[] sent, byte[] recv) {
        assertNotNull(sent);
        assertNotNull(recv);
        Assert.assertEquals(sent.length, recv.length);
        byte[] mac = Arrays.copyOf(recv, 6); // the destination mac
        Assert.assertEquals(epMac, new MAC(mac));
        mac = Arrays.copyOfRange(recv, 6, 12); // the source mac
        Assert.assertEquals(gwMac, new MAC(mac));
        // The rest of the packet should be identical. 
        Arrays.fill(sent, 0, 12, (byte) 0);
        Arrays.fill(recv, 0, 12, (byte) 0);
        Assert.assertArrayEquals(sent, recv);
    }

    /**
     * Check that we received an ICMP unreachable error of the specified type
     * from the specified address in reference to the specified packet.
     * @param recv
     * @param code
     * @param srcIp
     * @param triggerPkt
     */
    public void checkIcmpError(byte[] recv, UNREACH_CODE code,
            IntIPv4 srcIp, byte[] triggerPkt) {
        checkIcmpError(recv, code, gwMac, srcIp, epMac, epIp, triggerPkt);
    }

    public static void checkIcmpError(byte[] recv, UNREACH_CODE code, MAC dlSrc,
            IntIPv4 nwSrc, MAC dlDst, IntIPv4 nwDst, byte[] triggerPkt) {
        assertNotNull(recv);
        assertNotNull(triggerPkt);
        Ethernet pkt = new Ethernet();
        pkt.deserialize(recv, 0, recv.length);
        assertEquals(dlSrc, pkt.getSourceMACAddress());
        assertEquals(dlDst, pkt.getDestinationMACAddress());
        assertEquals(IPv4.ETHERTYPE, pkt.getEtherType());
        IPv4 ip = IPv4.class.cast(pkt.getPayload());
        assertEquals(nwSrc.address, ip.getSourceAddress());
        assertEquals(nwDst.address, ip.getDestinationAddress());
        assertEquals(ICMP.PROTOCOL_NUMBER, ip.getProtocol());
        ICMP icmp = ICMP.class.cast(ip.getPayload());
        assertTrue(icmp.isError());
        assertEquals(ICMP.TYPE_UNREACH, icmp.getType());
        assertEquals(code.toChar(), icmp.getCode());
        byte[] data = icmp.getData();
        byte[] expected = Arrays.copyOfRange(triggerPkt, 14,
                14+data.length);
        Assert.assertArrayEquals(expected, data);
    }
}
