/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.odp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.odp.flows.IPFragmentType;
import org.midonet.odp.flows.IpProtocol;
import org.midonet.packets.ARP;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPacket;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6;
import org.midonet.packets.MAC;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;

import static org.midonet.odp.flows.FlowKeys.arp;
import static org.midonet.odp.flows.FlowKeys.encap;
import static org.midonet.odp.flows.FlowKeys.etherType;
import static org.midonet.odp.flows.FlowKeys.ethernet;
import static org.midonet.odp.flows.FlowKeys.icmp;
import static org.midonet.odp.flows.FlowKeys.ipv4;
import static org.midonet.odp.flows.FlowKeys.ipv6;
import static org.midonet.odp.flows.FlowKeys.tcp;
import static org.midonet.odp.flows.FlowKeys.tcpFlags;
import static org.midonet.odp.flows.FlowKeys.udp;
import static org.midonet.odp.flows.FlowKeys.vlan;

public class FlowMatches {

    public static FlowMatch tcpFlow(String macSrc, String macDst,
                                    String ipSrc, String ipDst,
                                    int portSrc, int portDst,
                                    int flags) {
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
                .addKey(tcp(portSrc, portDst))
                .addKey(tcpFlags((short)flags));
    }

    public static FlowMatch fromEthernetPacket(Ethernet ethPkt) {
        return new FlowMatch(FlowKeys.fromEthernetPacket(ethPkt));
    }

    public static FlowMatch fromFlowKeys(ArrayList<FlowKey> keys) {
        FlowMatch flowMatch = new FlowMatch(keys);

        if (!flowMatch.isUsed(FlowMatch.Field.EtherType)) {
            // Match the empty ether type (802.2)
            flowMatch.setEtherType(
                FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_NONE).getEtherType());
        }
        return flowMatch;
    }
}
