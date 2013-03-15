/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.odp;

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


import static org.midonet.odp.flows.FlowKeys.*;

public class FlowMatches {

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
                        switch (icmpPkt.getType()) {
                            case ICMP.TYPE_ECHO_REPLY:
                            case ICMP.TYPE_ECHO_REQUEST:
                                match.addKey(icmpEcho(icmpPkt.getType(),
                                                      icmpPkt.getCode(),
                                                      icmpPkt.getIdentifier()));
                                break;
                            case ICMP.TYPE_UNREACH:
                            case ICMP.TYPE_TIME_EXCEEDED:
                            case ICMP.TYPE_PARAMETER_PROBLEM:
                                match.addKey(icmpError(icmpPkt.getType(),
                                                       icmpPkt.getCode(),
                                                       icmpPkt.getData()));
                                break;
                            default:
                                match.addKey(icmp(icmpPkt.getType(),
                                                  icmpPkt.getCode()));
                        }
                    default:
                        break;
                }
            default:
                break;
        }
        return match;
    }
}
