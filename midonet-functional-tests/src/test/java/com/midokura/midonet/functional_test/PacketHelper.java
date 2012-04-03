/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static java.util.Arrays.asList;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Assert;
import static junit.framework.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.array;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.ICMP.UNREACH_CODE;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;

public class PacketHelper {

    static Random rand = new Random();
    MAC epMac; // simulated endpoint's mac
    IntIPv4 epIp; // simulated endpoint's ip
    MAC gwMac;
    IntIPv4 gwIp;

    // This constructor can be used when the gwMac isn't known. Call setGwMac
    // when the gwMac is finally known.
    public PacketHelper(MAC mac, IntIPv4 ip, IntIPv4 gwIp) {
        this(mac, ip, null, gwIp);
    }

    public PacketHelper(MAC mac, IntIPv4 ip, MAC gwMac, IntIPv4 gwIp) {
        this.epMac = mac;
        this.epIp = ip;
        this.gwMac = gwMac;
        this.gwIp = gwIp;
    }

    public void setGwMac(MAC gwMac) {
        this.gwMac = gwMac;
    }

    /**
     * Make an ARP reply from the endpoint to the Gateway.
     *
     * @return the arp reply as a byte array
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
     *
     * @param recv is a byte array containing the arp reply we want to check
     * @throws MalformedPacketException
     */
    public MAC checkArpReply(byte[] recv) throws MalformedPacketException {
        return checkArpReply(recv, gwIp, epMac, epIp);
    }

    public static MAC checkArpReply(byte[] recv, IntIPv4 nwSrc,
            MAC dlDst, IntIPv4 nwDst) throws MalformedPacketException {
        assertThat("We actually have a packet buffer", recv, notNullValue());

        Ethernet pkt = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        pkt.deserialize(bb);
        assertThat("The packet ether type is ARP",
                   pkt.getEtherType(), equalTo(ARP.ETHERTYPE));
        MAC dlSrc = pkt.getSourceMACAddress();
        assertThat("The packet destination MAC is consistent with the target",
                   pkt.getDestinationMACAddress(), equalTo(dlDst));

        assertThat("The deserialized payload type is ARP",
                   pkt.getPayload(), instanceOf(ARP.class));
        ARP arp = ARP.class.cast(pkt.getPayload());

        assertThat("Some of the ARP packet fields are properly set",
                   arp,
                   allOf(
                       hasProperty("hardwareType",
                                   equalTo(ARP.HW_TYPE_ETHERNET)),
                       hasProperty("protocolType",
                                   equalTo(ARP.PROTO_TYPE_IP)),
                       hasProperty("hardwareAddressLength",
                                   equalTo((byte)6)),
                       hasProperty("protocolAddressLength",
                                   equalTo((byte)4)),
                       hasProperty("opCode", equalTo(ARP.OP_REPLY))
                   ));

        assertThat("The addresses of the ARP packet fields are properly set",
                   arp,
                   allOf(
                       hasProperty("senderHardwareAddress", equalTo(dlSrc)),
                       hasProperty("senderProtocolAddress",
                                   equalTo(
                                       IPv4.toIPv4AddressBytes(nwSrc.address))),
                       hasProperty("targetHardwareAddress",
                                   equalTo(dlDst)),
                       hasProperty("targetProtocolAddress",
                                   equalTo(
                                       IPv4.toIPv4AddressBytes(nwDst.address)))
                   ));
        return dlSrc;
    }

    /**
     * Make an ARP request to resolve the resolve the Gateway's ip.
     *
     * @return the arp request as a byte array
     */
    public byte[] makeArpRequest() {
        return makeArpRequest(epMac, epIp, gwIp);
    }

    public static byte[] makeArpRequest(MAC dlSrc, IntIPv4 nwSrc,
                                        IntIPv4 nwDst) {
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
     *
     * @param recv the arp request that we received
     * @throws MalformedPacketException
     */
    public void checkArpRequest(byte[] recv) throws MalformedPacketException {
        checkArpRequest(recv, gwMac, gwIp, epIp);
    }

    public static void checkArpRequest(byte[] recv, MAC dlSrc, IntIPv4 nwSrc,
            IntIPv4 nwDst) throws MalformedPacketException {

        assertThat("We expected a package that we didn't get.", recv, notNullValue());

        Ethernet pkt = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        pkt.deserialize(bb);

        assertThat("the package ether type is ARP",
                   pkt.getEtherType(), equalTo(ARP.ETHERTYPE));
        assertThat("the package MAC address matches the source MAC",
                   pkt.getSourceMACAddress(), equalTo(dlSrc));
        assertThat("the package destination MAC address is the broadcast mac",
                   pkt.getDestinationMACAddress(),
                   equalTo(MAC.fromString("ff:ff:ff:ff:ff:ff")));
        assertThat("the eth payload is an ARP payload",
                   pkt.getPayload(), instanceOf(ARP.class));

        ARP arp = ARP.class.cast(pkt.getPayload());
        assertThat("the ARP packet hardware type is ethernet",
                   arp.getHardwareType(), equalTo(ARP.HW_TYPE_ETHERNET));
        assertThat("the ARP packet protocol type is set to IP",
                   arp.getProtocolType(), equalTo(ARP.PROTO_TYPE_IP));
        assertThat("the ARP hardware address length is properly set",
                   arp.getHardwareAddressLength(), equalTo((byte) 6));
        assertThat("the ARP protocol address length is properly set",
                   arp.getProtocolAddressLength(), equalTo((byte) 4));
        assertThat("the ARP OpCode is set to request",
                   arp.getOpCode(), equalTo(ARP.OP_REQUEST));
        assertThat("the ARP sender hardware address is set to the source MAC",
                   arp.getSenderHardwareAddress(), equalTo(dlSrc));
        assertThat("the ARP sender protocol address is set to the source IP",
                   arp.getSenderProtocolAddress(),
                   equalTo(IPv4.toIPv4AddressBytes(nwSrc.address)));
        assertThat("the ARP target hardware address is set to the empty MAC",
                   arp.getTargetHardwareAddress(),
                   equalTo(MAC.fromString("00:00:00:00:00:00")));
        assertThat(
            "the ARP target protocol address is set to the destination IP",
            arp.getTargetProtocolAddress(),
            equalTo(IPv4.toIPv4AddressBytes(nwDst.address)));
    }

    /**
     * Check that the ICMP echo reply matches the request.
     *
     * @param request is the icmp request
     * @param reply   is the icmp request
     * @throws MalformedPacketException
     */
    public static void checkIcmpEchoReply(byte[] request, byte[] reply)
            throws MalformedPacketException {
        checkIcmpEchoReply(request, reply, null);
    }

    /**
     * Check that the ICMP echo reply matches the packet except it has the
     * specified src L3 address.
     *
     * @param request is the icmp request
     * @param reply   is the icmp request
     * @param srcIp   is the source IP we want to use for checking
     * @throws MalformedPacketException
     */
    public static void checkIcmpEchoReply(byte[] request, byte[] reply,
            IntIPv4 srcIp) throws MalformedPacketException {
        assertNotNull(request);
        assertNotNull(reply);
        assertEquals(request.length, reply.length);
        Ethernet pktRequest = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(request, 0, request.length);
        pktRequest.deserialize(bb);
        Ethernet pktReply = new Ethernet();
        bb = ByteBuffer.wrap(reply, 0, reply.length);
        pktReply.deserialize(bb);
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
     *
     * @param dstIp target ip we want to build the echo request for
     * @return the echo request as a byte array
     */
    public byte[] makeIcmpEchoRequest(IntIPv4 dstIp) {
        return makeIcmpEchoRequest(epMac, epIp, gwMac, dstIp);
    }

    public static byte[] makeIcmpEchoRequest(MAC dlSrc, IntIPv4 nwSrc,
                                             MAC dlDst, IntIPv4 nwDst) {
        short id = (short) rand.nextInt();
        short seq = (short) rand.nextInt();
        byte[] data = new byte[17];
        rand.nextBytes(data);
        ICMP icmp = new ICMP();
        icmp.setEchoRequest(id, seq, data);
        IPv4 ip = new IPv4();
        ip.setTtl((byte)12);
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
     *
     * @param sent is the serialization of the echo request
     * @param recv is the serialization of the reply we received
     */
    public void checkIcmpEchoRequest(byte[] sent, byte[] recv) {
        assertThat("The sent ICMP Echo buffer wasn't correct.", sent,
                   notNullValue());
        assertThat("The recv ICMP Reply buffer wasn't correct.", recv,
                   notNullValue());
        assertThat("The sent and received packages had different sizes.",
                   sent.length, equalTo(recv.length));

        byte[] mac = Arrays.copyOf(recv, 6); // the destination mac
        assertThat(
            "The destination mac address of the incoming package was different " +
                "from the port mac address.",
            new MAC(mac), equalTo(epMac));

        mac = Arrays.copyOfRange(recv, 6, 12); // the source mac
        assertThat("The source mac address of the incoming package was different " +
                       "from the gateway mac address.",
                   new MAC(mac), equalTo(gwMac));

        // The rest of the packet should be identical.
        Arrays.fill(sent, 0, 12, (byte) 0);
        Arrays.fill(recv, 0, 12, (byte) 0);
        assertThat("The rest of the package bytes were different.",
                   sent, equalTo(recv));
    }

    /**
     * Check that we received an ICMP unreachable error of the specified type
     * from the specified address in reference to the specified packet.
     *
     * @param recv       is the serialization of the ICMP reply
     * @param code       is the code want to validate against
     * @param srcIp      is the source ip from which tha package was sent
     * @param triggerPkt ?
     * @throws MalformedPacketException
     */
    public void checkIcmpError(byte[] recv, UNREACH_CODE code,
            IntIPv4 srcIp, byte[] triggerPkt) throws MalformedPacketException {
        checkIcmpError(recv, code, gwMac, srcIp, epMac, epIp, triggerPkt);
    }

    public static void checkIcmpError(byte[] recv, UNREACH_CODE code, MAC dlSrc,
            IntIPv4 nwSrc, MAC dlDst, IntIPv4 nwDst, byte[] triggerPkt)
                    throws MalformedPacketException {
        assertNotNull(recv);
        assertNotNull(triggerPkt);
        Ethernet pkt = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        pkt.deserialize(bb);
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
                                             14 + data.length);
        Assert.assertArrayEquals(expected, data);
    }
}
