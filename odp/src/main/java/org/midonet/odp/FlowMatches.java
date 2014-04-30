/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.midonet.odp.flows.*;
import org.midonet.packets.*;
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

        List<FlowKey> payloadKeys = new ArrayList<>();

        switch (ethPkt.getEtherType()) {
            case ARP.ETHERTYPE:
                if (ethPkt.getPayload() instanceof ARP) {
                    ARP arpPkt = ARP.class.cast(ethPkt.getPayload());
                    payloadKeys.add(
                        arp(arpPkt.getSenderHardwareAddress().getAddress(),
                            arpPkt.getTargetHardwareAddress().getAddress(),
                            arpPkt.getOpCode(),
                            IPv4Addr.bytesToInt(
                                arpPkt.getSenderProtocolAddress()),
                            IPv4Addr.bytesToInt(
                                arpPkt.getTargetProtocolAddress())));
                }
                break;
            case IPv4.ETHERTYPE:
                if (ethPkt.getPayload() instanceof IPv4) {
                    IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
                    parseFlowKeysFromIPv4(ipPkt, payloadKeys);
                }
                break;
            case IPv6.ETHERTYPE:
                if (ethPkt.getPayload() instanceof IPv6) {
                    IPv6 v6Pkt = IPv6.class.cast(ethPkt.getPayload());
                    parseFlowKeysFromIPv6(v6Pkt, match);
                }
                break;
        }

        if (!ethPkt.getVlanIDs().isEmpty()) {
            // process VLANS
            for (Iterator<Short> it = ethPkt.getVlanIDs().iterator();
                 it.hasNext(); ) {
                short vlanID = it.next();
                match.addKey(
                    etherType(it.hasNext() ? Ethernet.PROVIDER_BRIDGING_TAG :
                                  Ethernet.VLAN_TAGGED_FRAME));
                match.addKey(vlan(vlanID));
            }
            match.addKey(encap(payloadKeys));
        } else {
            match.addKeys(payloadKeys);
        }
        return match;
    }

    private static FlowKey.UserSpaceOnly makeIcmpFlowKey(ICMP icmp) {
        switch (icmp.getType()) {
            case ICMP.TYPE_ECHO_REPLY:
            case ICMP.TYPE_ECHO_REQUEST:
                return icmpEcho(icmp.getType(),
                                icmp.getCode(),
                                icmp.getIdentifier());
            case ICMP.TYPE_PARAMETER_PROBLEM:
            case ICMP.TYPE_REDIRECT:
            case ICMP.TYPE_SOURCE_QUENCH:
            case ICMP.TYPE_TIME_EXCEEDED:
            case ICMP.TYPE_UNREACH:
                return icmpError(icmp.getType(),
                                 icmp.getCode(),
                                 icmp.getData());
            default:
                return null;
        }
    }

    public static void addUserspaceKeys (Ethernet ethPkt, FlowMatch match) {
        for (FlowKey key: match.getKeys()) {
            if (key instanceof FlowKeyICMP) {
                ICMP icmpPkt = ICMP.class.cast(
                        IPv4.class.cast(ethPkt.getPayload()).
                                getPayload());
                FlowKey.UserSpaceOnly icmpUserSpace = makeIcmpFlowKey(icmpPkt);
                if (icmpUserSpace != null)
                    replaceKey(match, icmpUserSpace);
                return;
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
        List<FlowKey> keys = match.getKeys();
        int nKeys = keys.size();
        for (int i = 0; i < nKeys; i++) {
            FlowKey oldKey = keys.get(i);
            if (key.isChildOf(oldKey)) {
                // that cast is ugly, but hardly other way without refactoring
                // the FlowKey hierarchy to accommodate UserSpaceOnly.
                log.debug("Replacing key in FlowMatch: old {} new {}",
                          oldKey, key);
                keys.set(i, (FlowKey)key);
                match.setUserSpaceOnly(true);
                return;
            }
        }
    }

    private static void parseFlowKeysFromIPv4(IPv4 pkt, List<FlowKey> keys) {
        IPFragmentType fragmentType =
            IPFragmentType.fromIPv4Flags(pkt.getFlags(),
                                         pkt.getFragmentOffset());

        byte protocol = pkt.getProtocol();
        IPacket payload = pkt.getPayload();

        keys.add(
            ipv4(pkt.getSourceIPAddress(),
                 pkt.getDestinationIPAddress(),
                 protocol,
                 (byte) 0, /* type of service */
                 pkt.getTtl(),
                 fragmentType)
        );

        switch (protocol) {
            case TCP.PROTOCOL_NUMBER:
                if (payload instanceof TCP) {
                    TCP tcpPkt = TCP.class.cast(payload);
                    keys.add(tcp(tcpPkt.getSourcePort(),
                                 tcpPkt.getDestinationPort()));
                }
                break;
            case UDP.PROTOCOL_NUMBER:
                if (payload instanceof UDP) {
                    UDP udpPkt = UDP.class.cast(payload);
                    keys.add(udp(udpPkt.getSourcePort(),
                                 udpPkt.getDestinationPort()));
                }
                break;
            case ICMP.PROTOCOL_NUMBER:
                if (payload instanceof ICMP) {
                    ICMP icmpPkt = ICMP.class.cast(payload);
                    FlowKey icmpUserspace = makeIcmpFlowKey(icmpPkt);
                    if (icmpUserspace == null)
                        keys.add(icmp(icmpPkt.getType(),
                                      icmpPkt.getCode()));
                    else
                        keys.add(icmpUserspace);
                }
                break;
        }
    }

    private static void parseFlowKeysFromIPv6(IPv6 pkt, FlowMatch match) {
        match.addKey(
                ipv6(pkt.getSourceAddress(),
                     pkt.getDestinationAddress(),
                     pkt.getNextHeader()));

        IPacket payload = pkt.getPayload();
        switch (pkt.getNextHeader()) {
            case TCP.PROTOCOL_NUMBER:
                if (payload instanceof TCP) {
                    TCP tcpPkt = TCP.class.cast(payload);
                    match.addKey(tcp(tcpPkt.getSourcePort(),
                                     tcpPkt.getDestinationPort()));
                }
                break;
            case UDP.PROTOCOL_NUMBER:
                if (payload instanceof UDP) {
                    UDP udpPkt = UDP.class.cast(payload);
                    match.addKey(udp(udpPkt.getSourcePort(),
                                     udpPkt.getDestinationPort()));
                }
                break;
        }
    }

}
