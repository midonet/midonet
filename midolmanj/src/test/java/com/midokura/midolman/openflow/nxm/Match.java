/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import org.openflow.protocol.OFMatch;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.packets.ARP;
import com.midokura.packets.ICMP;
import com.midokura.packets.IPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.TCP;
import com.midokura.packets.UDP;

public class Match {

    public static OFMatch arp() throws NxmIOException {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)8);
        match.setDataLayerSource(MAC.fromString("12:23:56:78:cd:ef"));
        match.setDataLayerDestination(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        match.setDataLayerType(ARP.ETHERTYPE);
        match.setNetworkProtocol((byte) ARP.OP_REQUEST);
        match.setNetworkSource(IPv4.toIPv4Address("123.45.67.89"));
        match.setNetworkDestination(IPv4.toIPv4Address("98.76.64.32"));
        return match;
    }

    public static OFMatch arpInVlan() throws NxmIOException {
        MidoMatch match = MidoMatch.class.cast(arp());
        match.setDataLayerVirtualLan((short)0xbed);
        match.setDataLayerVirtualLanPriorityCodePoint((byte)3);
        return match;
    }

    public static OFMatch tcp() throws NxmIOException {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)12345);
        match.setDataLayerSource(MAC.fromString("18:21:74:93:aa:bb"));
        match.setDataLayerDestination(MAC.fromString("ab:cd:ef:fe:dc:ba"));
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(IPv4.toIPv4Address("10.100.200.0"), 24);
        match.setNetworkDestination(IPv4.toIPv4Address("192.168.1.4"), 30);
        match.setTransportSource((short) 43430);
        match.setTransportDestination((short)60001);
        return match;
    }

    public static OFMatch udp() throws NxmIOException {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)54321);
        match.setDataLayerSource(MAC.fromString("04:99:dd:93:aa:bb"));
        match.setDataLayerDestination(MAC.fromString("de:ad:be:ad:dc:ba"));
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        match.setNetworkSource(IPv4.toIPv4Address("10.122.200.0"), 24);
        match.setNetworkDestination(IPv4.toIPv4Address("192.168.2.4"), 30);
        match.setTransportSource((short)43430);
        match.setTransportDestination((short)60001);
        return match;
    }

    public static OFMatch icmp() throws NxmIOException {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short)22222);
        match.setDataLayerSource(MAC.fromString("04:99:dd:93:aa:bb"));
        match.setDataLayerDestination(MAC.fromString("de:ad:be:ad:dc:ba"));
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkSource(IPv4.toIPv4Address("10.122.200.0"), 32);
        match.setNetworkDestination(IPv4.toIPv4Address("192.168.2.4"), 32);
        match.setTransportSource((short)(ICMP.TYPE_UNREACH & 0xff));
        match.setTransportDestination(
                (short)(ICMP.UNREACH_CODE.UNREACH_HOST.toChar() & 0xff));
        return match;
    }

    public static OFMatch ipv6() throws NxmIOException {
        MidoMatch match = new MidoMatch();
        match.setInputPort((short) 22222);
        match.setDataLayerSource(MAC.fromString("04:99:dd:93:aa:bb"));
        match.setDataLayerDestination(MAC.fromString("de:ad:be:ad:dc:ba"));
        match.setDataLayerType((short) 0x86dd);  // IPv6
        return match;
    }
}
