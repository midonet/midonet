/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.odp;

import java.util.List;

import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyICMP;
import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.odp.flows.FlowKeyICMPError;
import org.midonet.packets.ARP;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.odp.flows.IPFragmentType;
import org.midonet.odp.flows.IpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.odp.flows.FlowKeys.*;

public class FlowMatches {

    private final static Logger log = LoggerFactory.getLogger(FlowMatches.class);

    public static FlowMatch tcpFlow(String macSrc, String macDst,
                                    String ipSrc, String ipDst,
                                    int portSrc, int portDst) {
        return
            new FlowMatch()
                .addKey(
                    ethernet(
                        MAC.fromString(macSrc).getAddress(),
                        MAC.fromString(macDst).getAddress()))
                .addKey(etherType(FlowKeyEtherType.Type.ETH_P_IP))
                .addKey(
                    ipv4(
                        IPv4Addr.fromString(ipSrc),
                        IPv4Addr.fromString(ipDst),
                        IpProtocol.TCP))
                .addKey(tcp(portSrc, portDst));
    }

    public static FlowMatch fromEthernetPacket(Ethernet ethPkt) {
        FlowMatch match = new FlowMatch()
            .addKey(
                ethernet(
                    ethPkt.getSourceMACAddress().getAddress(),
                    ethPkt.getDestinationMACAddress().getAddress()))
            .addKey(etherType(ethPkt.getEtherType()));
        switch (ethPkt.getEtherType()) {
            case ARP.ETHERTYPE:
                ARP arpPkt = ARP.class.cast(ethPkt.getPayload());
                match.addKey(
                    arp(
                        arpPkt.getSenderHardwareAddress().getAddress(),
                        arpPkt.getTargetHardwareAddress().getAddress())
                    .setOp(arpPkt.getOpCode())
                    .setSip(IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress()))
                    .setTip(IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress()))
                );
                break;
            case IPv4.ETHERTYPE:
                IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
                IPFragmentType fragmentType =
                    IPFragmentType.fromIPv4Flags(ipPkt.getFlags(),
                                                 ipPkt.getFragmentOffset());
                match.addKey(
                    ipv4(ipPkt.getSourceIPAddress(),
                         ipPkt.getDestinationIPAddress(),
                         ipPkt.getProtocol())
                    .setTtl(ipPkt.getTtl())
                    .setFrag(IPFragmentType.toByte(fragmentType))
                );
                switch (ipPkt.getProtocol()) {
                    case TCP.PROTOCOL_NUMBER:
                        TCP tcpPkt = TCP.class.cast(ipPkt.getPayload());
                        match.addKey(tcp(tcpPkt.getSourcePort(),
                                         tcpPkt.getDestinationPort())
                        );
                        break;
                    case UDP.PROTOCOL_NUMBER:
                        UDP udpPkt = UDP.class.cast(ipPkt.getPayload());
                        match.addKey(udp(udpPkt.getSourcePort(),
                                         udpPkt.getDestinationPort())
                        );
                        break;
                    case ICMP.PROTOCOL_NUMBER:
                        ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
                        match.addKey(makeIcmpFlowKey(icmpPkt));
                    default:
                        break;
                }
            default:
                break;
        }
        return match;
    }

    private static FlowKey<?> makeIcmpFlowKey(ICMP icmp) {
        switch (icmp.getType()) {
            case ICMP.TYPE_ECHO_REPLY:
            case ICMP.TYPE_ECHO_REQUEST:
                return icmpEcho(icmp.getType(),
                                icmp.getCode(),
                                icmp.getIdentifier());
            case ICMP.TYPE_UNREACH:
            case ICMP.TYPE_TIME_EXCEEDED:
            case ICMP.TYPE_PARAMETER_PROBLEM:
                return icmpError(icmp.getType(),
                                 icmp.getCode(),
                                 icmp.getData());
            default:
                return icmp(icmp.getType(), icmp.getCode());
        }
    }

    public static void addUserspaceKeys (Ethernet ethPkt, FlowMatch match) {
        switch (ethPkt.getEtherType()) {
            case IPv4.ETHERTYPE:
                IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
                if (ICMP.PROTOCOL_NUMBER == ipPkt.getProtocol()) {
                    ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
                    FlowKey<?> k = makeIcmpFlowKey(icmpPkt);
                    replaceKey(match, (FlowKey.UserSpaceOnly)k);
                }
        }
    }

    /**
     * TODO (galo) really not sure about this function.. would like a faster
     * way of replacing keys than risking various deletes in an ArrayList.
     *
     * However, in practise we're only going to call once, so it's actually
     * better than making a fresh copy of the match of the key list for example.
     *
     * @param match
     * @param key
     */
    private static void replaceKey(FlowMatch match, FlowKey.UserSpaceOnly key) {
        List<FlowKey<?>> keys = match.getKeys();
        int nKeys = keys.size();
        for (int i = 0; i < nKeys; i++) {
            FlowKey<?> oldKey = keys.get(i);
            if (key.isChildOf(oldKey)) {
                // that cast is ugly, but hardly other way without refactoring
                // the FlowKey hierarchy to accommodate UserSpaceOnly.
                log.debug("Replacing key in FlowMatch: old {} new {}",
                          oldKey, key);
                keys.set(i, (FlowKey<?>)key);
                match.setUserSpaceOnly(true);
                return;
            }
        }
    }

}
