/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.flows;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowKey;
import com.midokura.sdn.dp.flows.FlowKeyEtherType;
import com.midokura.sdn.dp.flows.FlowKeyEthernet;
import com.midokura.sdn.dp.flows.FlowKeyIPv4;
import com.midokura.sdn.dp.flows.FlowKeyInPort;
import com.midokura.sdn.dp.flows.FlowKeyTCP;
import com.midokura.sdn.dp.flows.FlowKeyTunnelID;
import com.midokura.sdn.dp.flows.FlowKeyUDP;
import com.midokura.sdn.dp.flows.FlowKeyVLAN;

/**
 * Class that provides an easy ways of building WildcardFlow objects.
 */
public class WildcardMatches {

    public static MidoMatch<?> fromFlowMatch(FlowMatch match) {
        return fromFlowMatch(match, new WildcardMatchImpl());
    }

    public static <Match extends MidoMatch<Match>> Match fromFlowMatch(FlowMatch match, Match wildcardMatch) {
        for (FlowKey<?> flowKey : match.getKeys()) {
            switch (flowKey.getKey().getId()) {
                case 1: // FlowKeyAttr<FlowKeyEncap> ENCAP = attr(1);
                    break;

                case 2: // FlowKeyAttr<FlowKeyPriority> PRIORITY = attr(2);
                    break;

                case 3: // FlowKeyAttr<FlowKeyInPort> IN_PORT = attr(3);
                    FlowKeyInPort inPort = as(flowKey, FlowKeyInPort.class);
                    wildcardMatch.setInputPortNumber((short) inPort.getInPort());
                    break;

                case 4: // FlowKeyAttr<FlowKeyEthernet> ETHERNET = attr(4);
                    FlowKeyEthernet ethernet = as(flowKey, FlowKeyEthernet.class);

                    wildcardMatch.setEthernetSource(new MAC(ethernet.getSrc()));
                    wildcardMatch.setEthernetDestination(
                        new MAC(ethernet.getDst()));
                    break;

                case 5:
                    FlowKeyVLAN vlan = as(flowKey, FlowKeyVLAN.class);

                    break;

                case 6:
                    FlowKeyEtherType etherType = as(flowKey, FlowKeyEtherType.class);

                    wildcardMatch.setEtherType(etherType.getEtherType());
                    break;

                case 7: // FlowKeyAttr<FlowKeyIPv4> IPv4 = attr(7);
                    FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);

                    wildcardMatch.setNetworkSource(new IntIPv4(ipv4.getSrc()));
                    wildcardMatch.setNetworkDestination(
                        new IntIPv4(ipv4.getDst()));
                    wildcardMatch.setNetworkProtocol(ipv4.getProto());
                    wildcardMatch.setIsIPv4Fragment(ipv4.getFrag() == 1);
                    break;

                case 8: // FlowKeyAttr<FlowKeyIPv6> IPv6 = attr(8);
                    break;

                case 9: //FlowKeyAttr<FlowKeyTCP> TCP = attr(9);
                    FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);

                    wildcardMatch.setTransportSource(tcp.getSrc());
                    wildcardMatch.setTransportDestination(tcp.getDst());
                    break;

                case 10: // FlowKeyAttr<FlowKeyUDP> UDP = attr(10);
                    FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);

                    wildcardMatch.setTransportSource(udp.getUdpSrc());
                    wildcardMatch.setTransportDestination(udp.getUdpDst());
                    break;

                case 11: // FlowKeyAttr<FlowKeyICMP> ICMP = attr(11);
                    break;

                case 12: // FlowKeyAttr<FlowKeyICMPv6> ICMPv6 = attr(12);
                    break;

                case 13: // FlowKeyAttr<FlowKeyARP> ARP = attr(13);
                    break;

                case 14: // FlowKeyAttr<FlowKeyND> ND = attr(14);
                    break;

                case 63: // FlowKeyAttr<FlowKeyTunnelID> TUN_ID = attr(63);
                    FlowKeyTunnelID tunnelID = as(flowKey, FlowKeyTunnelID.class);

                    wildcardMatch.setTunnelID(tunnelID.getTunnelID());
                    break;
            }
        }

        return wildcardMatch;
    }

    private static <Key extends FlowKey<Key>> Key as(FlowKey<?> flowKey, Class<Key> type) {
        return type.cast(flowKey.getValue());
    }
}
