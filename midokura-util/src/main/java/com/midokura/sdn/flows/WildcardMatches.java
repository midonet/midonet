/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.flows;

import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

import com.midokura.packets.ARP;
import com.midokura.packets.Ethernet;
import com.midokura.packets.ICMP;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.TCP;
import com.midokura.packets.UDP;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.FlowMatches;
import com.midokura.sdn.dp.flows.FlowKey;
import com.midokura.sdn.dp.flows.FlowKeyARP;
import com.midokura.sdn.dp.flows.FlowKeyEtherType;
import com.midokura.sdn.dp.flows.FlowKeyEthernet;
import com.midokura.sdn.dp.flows.FlowKeyICMP;
import com.midokura.sdn.dp.flows.FlowKeyIPv4;
import com.midokura.sdn.dp.flows.FlowKeyInPort;
import com.midokura.sdn.dp.flows.FlowKeyTCP;
import com.midokura.sdn.dp.flows.FlowKeyTunnelID;
import com.midokura.sdn.dp.flows.FlowKeyUDP;

/**
 * Class that provides an easy ways of building WildcardFlow objects.
 */
public class WildcardMatches {

    @Nullable
    public static ProjectedWildcardMatch project(Set<WildcardMatch.Field> fields, WildcardMatch source) {
        if (!source.getUsedFields().containsAll(fields))
            return null;

        return new ProjectedWildcardMatch(fields, source);
    }

    public static WildcardMatch fromFlowMatch(FlowMatch match) {
        return fromFlowMatch(match, new WildcardMatch());
    }

    public static WildcardMatch fromFlowMatch(FlowMatch match, WildcardMatch wildcardMatch) {
        List<FlowKey<?>> flowKeys = match.getKeys();
        processMatchKeys(wildcardMatch, flowKeys);
        return wildcardMatch;
    }

    public static WildcardMatch fromEthernetPacket(Ethernet ethPkt) {
        return fromFlowMatch(FlowMatches.fromEthernetPacket(ethPkt));
    }

    private static void processMatchKeys(WildcardMatch wildcardMatch, List<FlowKey<?>> flowKeys) {
        for (FlowKey<?> flowKey : flowKeys) {
            switch (flowKey.getKey().getId()) {
                case 1: // FlowKeyAttr<FlowKeyEncap> ENCAP = attr(1);
                    //FlowKeyEncap encap = as(flowKey, FlowKeyEncap.class);
                    //processMatchKeys(wildcardMatch, encap.getKeys());
                    break;

                case 2: // FlowKeyAttr<FlowKeyPriority> PRIORITY = attr(2);
                    break;

                case 3: // FlowKeyAttr<FlowKeyInPort> IN_PORT = attr(3);
                    FlowKeyInPort inPort = as(flowKey, FlowKeyInPort.class);

                    wildcardMatch.setInputPortNumber((short) inPort.getInPort());
                    break;

                case 4: // FlowKeyAttr<FlowKeyEthernet> ETHERNET = attr(4);
                    FlowKeyEthernet ethernet = as(flowKey, FlowKeyEthernet.class);

                    wildcardMatch
                        .setEthernetSource(new MAC(ethernet.getSrc()))
                        .setEthernetDestination(new MAC(ethernet.getDst()));
                    break;

                case 5: // FlowKeyAttr<FlowKeyVLAN> VLAN = attr(5);
                    //FlowKeyVLAN vlan = as(flowKey, FlowKeyVLAN.class);
                    break;

                case 6: // FlowKeyAttr<FlowKeyEtherType> ETHERTYPE = attr(6);
                    FlowKeyEtherType etherType = as(flowKey, FlowKeyEtherType.class);

                    wildcardMatch
                        .setEtherType(etherType.getEtherType());
                    break;

                case 7: // FlowKeyAttr<FlowKeyIPv4> IPv4 = attr(7);
                    FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);

                    wildcardMatch
                        .setNetworkSource(new IntIPv4(ipv4.getSrc()))
                        .setNetworkDestination(new IntIPv4(ipv4.getDst()))
                        .setNetworkProtocol(ipv4.getProto())
                        .setIsIPv4Fragment(ipv4.getFrag() == 1);

                    break;

                case 8: // FlowKeyAttr<FlowKeyIPv6> IPv6 = attr(8);
                    break;

                case 9: //FlowKeyAttr<FlowKeyTCP> TCP = attr(9);
                    FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);

                    wildcardMatch
                        .setTransportSource(tcp.getSrc())
                        .setTransportDestination(tcp.getDst())
                        .setNetworkProtocol(TCP.PROTOCOL_NUMBER);

                    break;

                case 10: // FlowKeyAttr<FlowKeyUDP> UDP = attr(10);
                    FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);

                    wildcardMatch
                        .setTransportSource(udp.getUdpSrc())
                        .setTransportDestination(udp.getUdpDst())
                        .setNetworkProtocol(UDP.PROTOCOL_NUMBER);

                    break;

                case 11: // FlowKeyAttr<FlowKeyICMP> ICMP = attr(11);
                    FlowKeyICMP icmp = as(flowKey, FlowKeyICMP.class);

                    wildcardMatch
                            .setTransportSource(icmp.getType())
                            .setTransportDestination(icmp.getCode())
                            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER);

                    break;

                case 12: // FlowKeyAttr<FlowKeyICMPv6> ICMPv6 = attr(12);
                    break;

                case 13: // FlowKeyAttr<FlowKeyARP> ARP = attr(13);
                    FlowKeyARP arp = as(flowKey, FlowKeyARP.class);
                    wildcardMatch
                            .setNetworkSource(new IntIPv4(arp.getSip()))
                            .setNetworkDestination(new IntIPv4(arp.getTip()))
                            .setEtherType(ARP.ETHERTYPE);
                    break;

                case 14: // FlowKeyAttr<FlowKeyND> ND = attr(14);
                    break;

                case 63: // FlowKeyAttr<FlowKeyTunnelID> TUN_ID = attr(63);
                    FlowKeyTunnelID tunnelID = as(flowKey, FlowKeyTunnelID.class);

                    wildcardMatch.setTunnelID(tunnelID.getTunnelID());
                    break;
            }
        }
    }

    private static <Key extends FlowKey<Key>> Key as(FlowKey<?> flowKey, Class<Key> type) {
        return type.cast(flowKey.getValue());
    }
}
