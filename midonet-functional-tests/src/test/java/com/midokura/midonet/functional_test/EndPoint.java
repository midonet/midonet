/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.nio.ByteBuffer;

import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.topology.TapWrapper;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class EndPoint {
    public TapWrapper tap;
    public MAC mac;
    public MAC gwMac;
    public IntIPv4 gwIp;
    public IntIPv4 ip;
    public IntIPv4 floatingIp;

    public EndPoint(IntIPv4 ip, MAC mac, IntIPv4 gwIp, MAC gwMac,
                     TapWrapper tap) {
        this.ip = ip;
        this.mac = mac;
        this.gwIp = gwIp;
        this.gwMac = gwMac;
        this.tap = tap;
        this.floatingIp = null;
    }

    public static class PacketPair {
        public byte[] sent;
        public byte[] received;

        public PacketPair(byte[] sent, byte[] received) {
            this.sent = sent;
            this.received = received;
        }
    }

    public static MAC exchangeArpWithGw(EndPoint ep)
            throws MalformedPacketException {
        assertThat("The ARP request was sent from the endpoint to the router.",
                ep.tap.send(PacketHelper.makeArpRequest(
                        ep.mac, ep.ip, ep.gwIp)));
        MAC dlDst = PacketHelper.checkArpReply(
                ep.tap.recv(), ep.gwIp, ep.mac, ep.ip);
        assertThat("The router's MAC is what we expected.",
                dlDst, equalTo(ep.gwMac));
        // Send unsolicited ARP replies so the router populates its ARP cache.
        assertThat("The unsolicited ARP reply was sent to the router.",
                ep.tap.send(PacketHelper.makeArpReply(
                        ep.mac, ep.ip, dlDst, ep.gwIp)));
        return dlDst;
    }

    public static void icmpDoesntArrive(EndPoint sender, EndPoint receiver,
                                  PacketPair packets) {
        assertThat("The packet was sent from the sender's tap.",
                sender.tap.send(packets.sent));
        assertThat("No packet arrives at the intended receiver.",
                receiver.tap.recv(), nullValue());
    }

    private static boolean sameSubnet(IntIPv4 ip1, IntIPv4 ip2) {
        // Assume all subnet masks are 24bits.
        int mask = 0xffffff00;
        return (ip1.getAddress() & mask) == (ip2.getAddress() & mask);
    }

    public static void retrySentPacket(EndPoint sender, EndPoint receiver,
                                 PacketPair packets) {
        assertThat("The packet was sent.",
                sender.tap.send(packets.sent));
        assertThat("The packet arrived.",
                receiver.tap.recv(),
                allOf(notNullValue(), equalTo(packets.received)));
    }

    public static PacketPair icmpTestOverBridge(EndPoint sender, EndPoint receiver)
            throws MalformedPacketException {
        return icmpTest(sender, receiver.ip, receiver, false);
    }

    public static PacketPair icmpTest(EndPoint sender, IntIPv4 dstIp, EndPoint receiver,
                                boolean dstIpTranslated)
            throws MalformedPacketException {
        if (null == dstIp) {
            dstIp = receiver.ip;
            dstIpTranslated = false;
        }

        // Choose the dstMac based on whether dstIp is in the sender's subnet.
        boolean sameSubnet = sameSubnet(sender.ip, dstIp);
        byte[] sent = PacketHelper.makeIcmpEchoRequest(
                sender.mac, sender.ip,
                sameSubnet? receiver.mac : sender.gwMac, dstIp);
        assertThat("The packet should have been sent from the source tap.",
                sender.tap.send(sent));
        byte[] received = receiver.tap.recv();
        assertThat("The packet should have arrived at the destination tap.",
                received, notNullValue());
        Ethernet ethPkt = new Ethernet();
        ethPkt.deserialize(ByteBuffer.wrap(received));
        // The srcMac depends on whether dstIp is in the sender's subnet.
        assertThat("The received pkt's src Mac",
                ethPkt.getSourceMACAddress(),
                equalTo(sameSubnet? sender.mac : receiver.gwMac));
        assertThat("The received pkt's dst Mac should be the dst endpoint's",
                ethPkt.getDestinationMACAddress(), equalTo(receiver.mac));
        assertThat("It's an IP pkt.", ethPkt.getEtherType(),
                equalTo(IPv4.ETHERTYPE));
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        // The pkt's srcIp depends on whether the dstIp is in the sender's
        // subnet and whether the sender has a floatingIP.
        IntIPv4 srcIP = sameSubnet
                ? sender.ip
                : (sender.floatingIp != null)? sender.floatingIp : sender.ip;
        assertThat("The src IP", ipPkt.getSourceAddress(),
                equalTo(srcIP.getAddress()));
        // The pkt's nwDst depends on whether the dstIp is translated.
        assertThat("The dst IP is the dst endpoint's",
                ipPkt.getDestinationAddress(),
                equalTo(dstIpTranslated ?
                        receiver.ip.getAddress() : dstIp.getAddress()));

        // If we reset the fields that were translated, the packets should be
        // identical.
        ipPkt.setSourceAddress(sender.ip.getAddress());
        ipPkt.setDestinationAddress(dstIp.getAddress());
        // Reset the IPv4 pkt's checksum so that it's recomputed.
        ipPkt.setChecksum((short)0);
        ethPkt.setSourceMACAddress(sender.mac);
        ethPkt.setDestinationMACAddress(
                sameSubnet? receiver.mac : sender.gwMac);
        assertThat("The sent and received packet should now be identical.",
                ethPkt.serialize(), equalTo(sent));
        return new PacketPair(sent, received);
    }
}
