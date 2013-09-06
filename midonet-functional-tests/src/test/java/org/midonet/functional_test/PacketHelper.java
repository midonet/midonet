/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.ARP;
import org.midonet.packets.BPDU;
import org.midonet.packets.Data;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.ICMP.UNREACH_CODE;
import org.junit.Assert;
import org.midonet.packets.IPacket;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.UDP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static junit.framework.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


public class PacketHelper {
    private final static Logger log = LoggerFactory.getLogger(PacketHelper.class);

    static Random rand = new Random();
    MAC epMac; // simulated endpoint's mac
    IPv4Addr epIp; // simulated endpoint's ip
    MAC gwMac;
    IPv4Addr gwIp;

    // This constructor can be used when the gwMac isn't known. Call setGwMac
    // when the gwMac is finally known.
    public PacketHelper(MAC mac, IPv4Addr ip, IPv4Addr gwIp) {
        this(mac, ip, null, gwIp);
    }

    public PacketHelper(MAC mac, IPv4Addr ip, MAC gwMac, IPv4Addr gwIp) {
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

    public static byte[] makeArpReply(MAC dlSrc, IPv4Addr nwSrc,
                                      MAC dlDst, IPv4Addr nwDst) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.addr()));
        arp.setTargetHardwareAddress(dlDst);
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwDst.addr()));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(dlDst);
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

    public static byte[] makeUDPPacket(MAC dlSrc, IPv4Addr ipSrc, MAC dlDst,
                                       IPv4Addr ipDst, short tpSrc, short tpDst,
                                       byte[] payload) {
        UDP udp = new UDP();
        udp.setSourcePort(tpSrc);
        udp.setDestinationPort(tpDst);
        udp.setPayload(new Data(payload));
        IPv4 ip = new IPv4();
        ip.setProtocol(UDP.PROTOCOL_NUMBER).setSourceAddress(ipSrc)
          .setDestinationAddress(ipDst).setPayload(udp);
        Ethernet frame = new Ethernet();
        frame.setSourceMACAddress(dlSrc).setDestinationMACAddress(dlDst)
             .setEtherType(IPv4.ETHERTYPE).setPayload(ip);
        return frame.serialize();
    }

    public static byte[] makeBPDU(MAC srcMac, MAC dstMac, byte type, byte flags,
                                  long rootBridgeId, int rootPathCost,
                                  long senderBridgeId, short portId,
                                  short msgAge, short maxAge, short helloTime,
                                  short fwdDelay) {
        BPDU bpdu = new BPDU();
        bpdu.setBpduMsgType(type)
            .setFlags(flags)
            .setRootBridgeId(rootBridgeId)
            .setRootPathCost(rootPathCost)
            .setSenderBridgeId(senderBridgeId)
            .setPortId(portId)
            .setMsgAge(msgAge)
            .setMaxAge(maxAge)
            .setHelloTime(helloTime)
            .setFwdDelay(fwdDelay);
        Ethernet frame = new Ethernet();
        frame.setEtherType(BPDU.ETHERTYPE);
        frame.setPayload(bpdu);
        frame.setSourceMACAddress(srcMac);
        frame.setDestinationMACAddress(dstMac);
        return frame.serialize();
    }

    /**
     * Check that the packet is an ARP reply from the gateway to the endpoint.
     *
     * @param recv is a byte array containing the arp reply we want to check
     * @throws MalformedPacketException
     */
    public MAC checkArpReply(byte[] recv) throws MalformedPacketException {
        assertThat("We actually have a packet buffer", recv, notNullValue());

        Ethernet frame = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        frame.deserialize(bb);

        return checkArpReply(frame, gwIp, epMac, epIp);
    }

    /**
     * Check that the packet is an ARP reply from the gateway to the endpoint.
     *
     * @param frame is the Ethernet frame containing the ARP packet.
     *
     * @throws MalformedPacketException
     */
    public MAC checkArpReply(Ethernet frame) throws MalformedPacketException {
        return checkArpReply(frame, gwIp, epMac, epIp);
    }

    public static MAC checkArpReply(byte[] recv, IPv4Addr nwSrc,
                                    MAC dlDst, IPv4Addr nwDst)
            throws MalformedPacketException {
        assertThat("We actually have a packet buffer", recv, notNullValue());

        Ethernet pkt = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        pkt.deserialize(bb);

        return checkArpReply(pkt, nwSrc, dlDst, nwDst);
    }

    public static MAC checkArpReply(Ethernet frame,
                                    IPv4Addr nwSrc, MAC dlDst,
                                    IPv4Addr nwDst) {

        assertThat("We expected an ARP packet",
                   frame.getEtherType(), equalTo(ARP.ETHERTYPE));
        MAC dlSrc = frame.getSourceMACAddress();
        assertThat("The packet destination MAC is consistent with the target",
                   frame.getDestinationMACAddress(), equalTo(dlDst));

        assertThat("The deserialized payload type is ARP",
                   frame.getPayload(), instanceOf(ARP.class));
        ARP arp = ARP.class.cast(frame.getPayload());

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
                                       IPv4.toIPv4AddressBytes(nwSrc.addr()))),
                       hasProperty("targetHardwareAddress",
                                   equalTo(dlDst)),
                       hasProperty("targetProtocolAddress",
                                   equalTo(
                                       IPv4.toIPv4AddressBytes(nwDst.addr())))
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

    public static byte[] makeArpRequest(MAC dlSrc, IPv4Addr nwSrc,
                                        IPv4Addr nwDst) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.addr()));
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwDst.addr()));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);
        return pkt.serialize();
    }

    /**
     * Takes a serialized Ethernet frame, adds the given VLAN ID, and
     * seriaizes the packet again.
     *
     * @param ethBytes
     * @param vlanId
     * @return
     */
    public static byte[] addVlanId(byte[] ethBytes, short vlanId)
        throws MalformedPacketException {
        Ethernet pkt = Ethernet.deserialize(ethBytes);
        pkt.setVlanID(vlanId);
        return pkt.serialize();
    }

    public static byte[] delVlanId(byte[] ethBytes)
        throws MalformedPacketException {
        Ethernet pkt = Ethernet.deserialize(ethBytes);
        pkt.setVlanID((short)0);
        return pkt.serialize();
    }

    /**
     * Check that the packet is an ARP request from the gateway to the endpoint.
     *
     * @param frameBuffer the arp request that we received
     * @throws MalformedPacketException
     */
    public void checkArpRequest(byte[] frameBuffer) throws MalformedPacketException {
        assertThat("We actually have a packet buffer.", frameBuffer, notNullValue());

        Ethernet frame = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(frameBuffer, 0, frameBuffer.length);
        frame.deserialize(bb);

        checkArpRequest(frame);
    }

    /**
     * Check that the packet is an ARP request from the gateway to the endpoint.
     *
     * @param frame the ethernet frame that contains the ARP packet.
     * @throws MalformedPacketException
     */
    public void checkArpRequest(Ethernet frame) throws MalformedPacketException {
        checkArpRequest(frame, gwMac, gwIp, epIp);
    }

    public static void checkArpRequest(byte[] frameBuffer, MAC dlSrc,
                                       IPv4Addr nwSrc, IPv4Addr nwDst)
        throws MalformedPacketException {

        assertThat("We actually have a packet buffer.", frameBuffer, notNullValue());

        Ethernet frame = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(frameBuffer, 0, frameBuffer.length);
        frame.deserialize(bb);

        checkArpRequest(frame, dlSrc, nwSrc, nwDst);
    }

    public static void checkArpRequest(Ethernet frame, MAC dlSrc,
                                       IPv4Addr nwSrc, IPv4Addr nwDst)
        throws MalformedPacketException {

        assertThat("the packet ether type is ARP",
                   frame.getEtherType(), equalTo(ARP.ETHERTYPE));
        assertThat("the packet MAC address matches the source MAC",
                   frame.getSourceMACAddress(), equalTo(dlSrc));
        assertThat("the packet destination MAC address is the broadcast mac",
                   frame.getDestinationMACAddress(),
                   equalTo(MAC.fromString("ff:ff:ff:ff:ff:ff")));
        assertThat("the eth payload is an ARP payload",
                   frame.getPayload(), instanceOf(ARP.class));

        ARP arp = ARP.class.cast(frame.getPayload());
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
                   equalTo(IPv4.toIPv4AddressBytes(nwSrc.addr())));
        assertThat("the ARP target hardware address is set to the empty MAC",
                   arp.getTargetHardwareAddress(),
                   equalTo(MAC.fromString("00:00:00:00:00:00")));
        assertThat(
            "the ARP target protocol address is set to the destination IP",
            arp.getTargetProtocolAddress(),
            equalTo(IPv4.toIPv4AddressBytes(nwDst.addr())));
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
            IPv4Addr srcIp) throws MalformedPacketException {
        assertNotNull(request);
        assertNotNull(reply);
        Ethernet pktRequest = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(request, 0, request.length);
        pktRequest.deserialize(bb);
        Ethernet pktReply = new Ethernet();
        bb = ByteBuffer.wrap(reply, 0, reply.length);
        pktReply.deserialize(bb);
        log.info("ICMP REQUEST: " + pktRequest.toString());
        log.info("ICMP REPLY: " + pktReply.toString());
        assertEquals(request.length, reply.length);
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
            assertEquals(srcIp.addr(),
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
     * Check that an ICMP packet represents an ICMP unreachable error
     * specified src L3 address.
     *
     * @param unreach the ICMP unreachable frame
     * @param request the undeliverable packet
     * @throws MalformedPacketException
     */
    public static void checkIcmpUnreachable(byte[] request, byte[] unreach)
            throws MalformedPacketException {
        assertNotNull(unreach);
        assertNotNull(request);

        Ethernet ethRequest = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(request, 0, request.length);
        ethRequest.deserialize(bb);

        Ethernet ethUnreach = new Ethernet();
        bb = ByteBuffer.wrap(unreach, 0, unreach.length);
        ethUnreach.deserialize(bb);

        assertEquals(IPv4.ETHERTYPE, ethRequest.getEtherType());
        assertEquals(IPv4.ETHERTYPE, ethUnreach.getEtherType());

        IPv4 ipRequest = IPv4.class.cast(ethRequest.getPayload());
        IPv4 ipUnreach = IPv4.class.cast(ethUnreach.getPayload());

        assertEquals(ipRequest.getSourceAddress(), ipUnreach.getDestinationAddress());
        assertEquals(ICMP.PROTOCOL_NUMBER, ipUnreach.getProtocol());
        ICMP icmpUnreach = ICMP.class.cast(ipUnreach.getPayload());
        assertEquals(icmpUnreach.getType(), ICMP.TYPE_UNREACH);
    }

    /**
     * Make an ICMP request from the endpoint to the specified destination.
     * Use the gw's mac as the L2 destination.
     *
     * @param dstIp target ip we want to build the echo request for
     * @return the echo request as a byte array
     */
    public byte[] makeIcmpEchoRequest(IPv4Addr dstIp) {
        return makeIcmpEchoRequest(epMac, epIp, gwMac, dstIp);
    }

    public static byte[] makeIcmpEchoRequest(MAC dlSrc, IPv4Addr nwSrc,
                                             MAC dlDst, IPv4Addr nwDst) {
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
        ip.setSourceAddress(nwSrc.addr());
        ip.setDestinationAddress(nwDst.addr());
        Ethernet pkt = new Ethernet();
        pkt.setPayload(ip);
        pkt.setEtherType(IPv4.ETHERTYPE);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(dlDst);
        return pkt.serialize();
    }

    public static byte[] makeIcmpEchoReply(MAC dlSrc, IPv4Addr nwSrc,
                                           MAC dlDst, IPv4Addr nwDst,
                                           short id, short seq) {
        byte[] data = new byte[17];
        rand.nextBytes(data);
        ICMP icmp = new ICMP();
        icmp.setEchoReply(id, seq, data);
        IPv4 ip = new IPv4();
        ip.setTtl((byte)12);
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setSourceAddress(nwSrc.addr());
        ip.setDestinationAddress(nwDst.addr());
        Ethernet pkt = new Ethernet();
        pkt.setPayload(ip);
        pkt.setEtherType(IPv4.ETHERTYPE);
        pkt.setSourceMACAddress(dlSrc);
        pkt.setDestinationMACAddress(dlDst);
        return pkt.serialize();
    }

    public static byte[] makeIcmpErrorUnreachable(MAC dlSrc, MAC dlDst,
                                       ICMP.UNREACH_CODE code, IPv4 forPkt) {
        ICMP icmp = new ICMP();
        icmp.setUnreachable(code, forPkt);

        IPv4 ip = new IPv4();
        ip.setSourceAddress(forPkt.getDestinationAddress());
        ip.setDestinationAddress(forPkt.getSourceAddress());
        ip.setTtl((byte)12);
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);

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
    public void checkIcmpEchoRequest(byte[] sent, byte[] recv) throws MalformedPacketException {
        assertThat("The sent ICMP Echo buffer wasn't correct.", sent, notNullValue());
        assertThat("The recv ICMP Echo buffer wasn't correct.", recv, notNullValue());
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

        Ethernet sentPacket = Ethernet.deserialize(sent);
        Ethernet recvPacket = Ethernet.deserialize(recv);

        IPv4 sentIpPacket = (IPv4) sentPacket.getPayload();
        IPv4 recvIpPacket = (IPv4) recvPacket.getPayload();

        assertThat("The TTL of the received packet is bigger or the same as the sent packet",
                1,
                equalTo(Byte.compare(sentIpPacket.getTtl(), recvIpPacket.getTtl())));


        assertThat("Checkums are the same", sentIpPacket.getChecksum(), not(recvIpPacket.getChecksum()));

        assertThat("Protocol is not the same", sentIpPacket.getProtocol(), equalTo(recvIpPacket.getProtocol()));

    }

    /**
     * Check that we received an ICMP unreachable error of the specified type
     * from the specified address in reference to the specified packet.
     *
     * @param recv       is the serialization of the ICMP reply
     * @param code       is the code want to validate against
     * @param srcIp      is the source ip from which tha package was sent
     * @param triggerPkt the packet triggering the error
     * @throws MalformedPacketException
     */
    public void checkIcmpError(byte[] recv, UNREACH_CODE code,
            IPv4Addr srcIp, byte[] triggerPkt) throws MalformedPacketException {
        checkIcmpError(recv, code, gwMac, srcIp, epMac, epIp, triggerPkt);
    }

    public static void checkIcmpError(byte[] recv, UNREACH_CODE code, MAC dlSrc,
            IPv4Addr nwSrc, MAC dlDst, IPv4Addr nwDst, byte[] triggerPkt)
                    throws MalformedPacketException {
        Assert.assertThat("Received data is null", recv, notNullValue());
        Assert.assertThat("Trigger packet is null", triggerPkt, notNullValue());
        Ethernet pkt = new Ethernet();
        ByteBuffer bb = ByteBuffer.wrap(recv, 0, recv.length);
        pkt.deserialize(bb);
        assertEquals(dlSrc, pkt.getSourceMACAddress());
        assertEquals(dlDst, pkt.getDestinationMACAddress());
        assertEquals(IPv4.ETHERTYPE, pkt.getEtherType());
        IPv4 ip = IPv4.class.cast(pkt.getPayload());
        assertEquals(nwSrc.addr(), ip.getSourceAddress());
        assertEquals(nwDst.addr(), ip.getDestinationAddress());
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

    public static void matchUdpPacket(TapWrapper device,
                                      MAC fromMac, IPv4Addr fromIp,
                                      MAC toMac, IPv4Addr toIp,
                                      short udpSrc, short udpDst)
            throws MalformedPacketException {
        byte[] received = device.recv();
        assertNotNull(String.format("Expected packet on %s", device.getName()),
                received);
        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on " + device.getName() + ": " + eth.toString());
        matchUdpPacket(eth, fromMac, fromIp, toMac, toIp, udpSrc, udpDst);
    }

    public static void matchUdpPacket(IPacket pkt,
                                      MAC fromMac, IPv4Addr fromIp,
                                      MAC toMac, IPv4Addr toIp,
                                      short udpSrc, short udpDst)
            throws MalformedPacketException {
        assertTrue("packet is ethernet", pkt instanceof Ethernet);
        Ethernet eth = (Ethernet) pkt;

        assertEquals("source ethernet address",
                fromMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
                toMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
                fromIp.addr(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
                toIp.addr(), ipPkt.getDestinationAddress());

        assertTrue("payload is UDP", ipPkt.getPayload() instanceof UDP);
        UDP udpPkt = (UDP) ipPkt.getPayload();
        assertEquals("udp source port",
                udpSrc, udpPkt.getSourcePort());
        assertEquals("udp destination port",
                udpDst, udpPkt.getDestinationPort());
    }

    /**
     * Send an XID (802.2 over 802.3) broadcast packet from the endpoint.
     *
     * @return the XID packet as a byte array
     */
    public byte[] makeXIDPacket() {
        return makeXIDPacket(epMac);
    }

    public static byte[] makeXIDPacket(MAC dlSrc) {
        // Handmade XID / LLC packet
        byte[] xidData = {(byte) 0x00, (byte) 0x01,
                (byte) 0xaf, (byte) 0x81,
                (byte) 0x01, (byte) 0x00};

        // Simple data packet to hold xid contents
        IPacket xidPacket = new Data(xidData);

        // Make an 802.3 packet to hold the XID data
        Ethernet pkt = new Ethernet().setEtherType((short) xidData.length).
                setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff")).
                setSourceMACAddress(dlSrc).
                setPad(true);
        pkt.setPayload(xidPacket);
        return pkt.serialize();
    }
}
