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
import java.util.Random;

import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.odp.flows.IpProtocol;
import org.midonet.packets.Ethernet;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.odp.flows.FlowKeys.*;

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

    public static FlowMatch generateFlowMatch(Random rand) {
        ArrayList<FlowKey> keys = new ArrayList<>();

        keys.add(inPort(rand.nextInt()));

        if (rand.nextInt(4) == 0) {
            keys.add(FlowKeys.tunnel(rand.nextLong(), rand.nextInt(),
                                     rand.nextInt(), (byte) 0));
        }

        keys.add(FlowKeys.ethernet(MAC.random().getAddress(),
                                   MAC.random().getAddress()));

        if (rand.nextInt(2) == 0) {
            keys.add(vlan((short) rand.nextInt()));
        }

        if (rand.nextInt(3) == 0) {
            IpProtocol proto;
            switch (rand.nextInt(3)) {
                case 2:
                    proto = IpProtocol.ICMP;
                    keys.add(icmp((byte) rand.nextInt(), (byte) rand.nextInt()));
                    break;
                case 1:
                    proto = IpProtocol.UDP;
                    keys.add(udp(rand.nextInt() & 0xffff, rand.nextInt() & 0xffff));
                    break;
                default:
                    proto = IpProtocol.TCP;
                    keys.add(tcp(rand.nextInt() & 0xffff, rand.nextInt() & 0xffff));
            }
            keys.add(FlowKeys.ipv4(IPv4Addr.fromInt(rand.nextInt()),
                                   IPv4Addr.fromInt(rand.nextInt()),
                                   proto));
            keys.add(etherType(FlowKeyEtherType.Type.ETH_P_IP));
        } else if (rand.nextInt(3) == 0) {
            keys.add(arp(MAC.random().getAddress(), MAC.random().getAddress(),
                         (short) rand.nextInt(), IPv4Addr.random().toInt(),
                         IPv4Addr.random().toInt()));
            keys.add(etherType(FlowKeyEtherType.Type.ETH_P_ARP));
        } else {
            keys.add(etherType(FlowKeyEtherType.Type.ETH_P_NONE));
        }

        return new FlowMatch(keys);
    }
}
