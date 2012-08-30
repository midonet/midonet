/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp;

import com.midokura.packets.ARP;
import com.midokura.packets.Ethernet;
import com.midokura.packets.ICMP;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.TCP;
import com.midokura.packets.UDP;
import com.midokura.sdn.dp.flows.FlowKeyEtherType;
import com.midokura.sdn.dp.flows.IpProtocol;


import static com.midokura.sdn.dp.flows.FlowKeys.*;

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
                        IntIPv4.fromString(ipSrc).addressAsInt(),
                        IntIPv4.fromString(ipDst).addressAsInt(),
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
                match.addKey(
                    ipv4(ipPkt.getSourceAddress(), ipPkt.getDestinationAddress(),
                        ipPkt.getProtocol())
                    .setTtl(ipPkt.getTtl())
                );
                switch (ipPkt.getProtocol()) {
                    case TCP.PROTOCOL_NUMBER:
                        TCP tcpPkt = TCP.class.cast(ipPkt.getPayload());
                        match.addKey(
                            tcp(tcpPkt.getSourcePort(),
                                tcpPkt.getDestinationPort())
                        );
                        break;
                    case UDP.PROTOCOL_NUMBER:
                        UDP udpPkt = UDP.class.cast(ipPkt.getPayload());
                        match.addKey(
                            udp(udpPkt.getSourcePort(),
                                udpPkt.getDestinationPort())
                        );
                        break;
                    case ICMP.PROTOCOL_NUMBER:
                        ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
                        match.addKey(
                            icmp(icmpPkt.getType(), icmpPkt.getCode())
                        );
                        break;
                    default:
                        break;
                }
            default:
                break;
        }
        return match;
    }
}
