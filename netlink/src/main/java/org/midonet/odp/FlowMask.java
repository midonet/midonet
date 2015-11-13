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

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkSerializable;
import org.midonet.netlink.Reader;
import org.midonet.odp.flows.*;

import static org.midonet.odp.OpenVSwitch.FlowKey.Attr.*;

/**
 * An OVS flow mask. A flow mask is a list of FlowKeys whose values are
 * interpreted as a mask to apply on the FlowKeys of received packets when
 * querying the kernel flow table. The values can either be all 0s or all 1s.
 * The former is a don't care value, effectively wildcarding the corresponding
 * FlowKey field, whereas the latter requires the exact value of the FlowKey
 * field to be used in querying the flow table.
 *
 * Some FlowKeys in the mask require FlowKeys of a lower network layer to also
 * be present and to contain exact match values. Namely, an exact match in a
 * transport layer FlowKey requires an IPv4 or IPv6 FlowKey with an exact match
 * for the protocol field. Similarly, specifying a FlowKey for a network
 * protocol requires the EtherType FlowKey to be present with an exact match.
 *
 * The absence of a flow mask when serializing a Flow translates into an
 * exact match for all of the Flow's FlowKeys. The absence of a FlowKey in a
 * mask means the fields of that FlowKey are considered wildcarded. This allows
 * us to only serialize the FlowKeys that contain an exact match value.
 *
 * (From OVS 2.0 docs:) The behavior when using overlapping wildcarded flows is
 * undefined. It is the responsibility of the user space program to ensure that
 * any incoming packet can match at most one flow, wildcarded or not. The current
 * implementation performs best-effort detection of overlapping wildcarded flows
 * and may reject some but not all of them. However, this behavior may change in
 * future versions.
 *
 * Note: We never get layer 4 FlowKeys for IP packets with fragmentation type
 *       "Later", so we always require an exact match on the IP fragmentation
 *       type field. This ensures that if a packet with "Later" fragmentation
 *       arrives, which will have layer 4 fields wildcarded, we don't conflict
 *       with a previous or subsequent flow for packets with different
 *       fragmentation types.
 *
 * @see FlowKey
 * @see Flow
 */
public class FlowMask implements NetlinkSerializable, AttributeHandler {
    private final static long EXACT_64 = ~0L;
    private final static int EXACT_32 = ~0;
    private final static short EXACT_16 = (short) ~0;
    private final static byte EXACT_8 = (byte) ~0;

    private static class MaskableFlowKeyVlan extends FlowKeyVLAN {

        public MaskableFlowKeyVlan() {
            super((short) 0);
        }

        @Override
        public int serializeInto(ByteBuffer buffer) {
            buffer.putShort(vlan);
            return 2;
        }

        @Override
        public void deserializeFrom(ByteBuffer buf) {
            vlan = buf.getShort();
        }
    }

    private static class MaskableFlowKeyInPort extends FlowKeyInPort {

        @Override
        public void deserializeFrom(ByteBuffer buf) {
            super.deserializeFrom(buf);
            // The kernel gives us back the port number in the lower 16
            // bits and the mask in the upper 16 bits. The input port is
            // always an exact match.
            portNo = EXACT_32;
        }

        @Override
        public void wildcard() {
            portNo = EXACT_32;
        }
    }

    private FlowKey[] keys = new FlowKey[OpenVSwitch.FlowKey.Attr.MAX];
    {
        for (short i = 0; i < keys.length; ++i) {
            keys[i] = FlowKeys.newBlankInstance(i);
        }
        keys[OpenVSwitch.FlowKey.Attr.InPort] = new MaskableFlowKeyInPort();
        keys[OpenVSwitch.FlowKey.Attr.VLan] = new MaskableFlowKeyVlan();
    }

    private long keysWithExactMatch;

    public FlowKey[] getKeys() {
        return keys;
    }

    public FlowMask() {
        clear();
    }

    public void clear() {
        for (FlowKey k : keys) {
            if (k != null)
                k.wildcard();
        }
        keysWithExactMatch = 1L << OpenVSwitch.FlowKey.Attr.InPort;
    }

    @SuppressWarnings("unchecked")
    private <T extends FlowKey> T key(short id) {
        return (T)(keys[id]);
    }

    private void exactMatchInKey(short id) {
        keysWithExactMatch |= 1L << id;
    }

    public FlowKey getMaskFor(short keyId) {
        return keys[keyId & MASK];
    }

    /**
     * Calculate the flow mask from the specified FlowMatch.
     * The input port is always an exact match.
     */
    public void calculateFor(FlowMatch fmatch) {
        FlowKeyTunnel tunnel = key(Tunnel);
        if (fmatch.isSeen(FlowMatch.Field.TunnelKey)) {
            tunnel.tun_id = EXACT_64;
            exactMatchInKey(Tunnel);
        }
        if (fmatch.isSeen(FlowMatch.Field.TunnelSrc)) {
            tunnel.ipv4_src = EXACT_32;
            exactMatchInKey(Tunnel);
        }
        if (fmatch.isSeen(FlowMatch.Field.TunnelDst)) {
            tunnel.ipv4_dst = EXACT_32;
            exactMatchInKey(Tunnel);
        }
        short highestLayer = fmatch.highestLayerSeen();
        if (highestLayer >= 2) {
            maskLayer2(fmatch, highestLayer);
        } else if (!fmatch.isUsed(FlowMatch.Field.EtherType)) {
            // This is an IEEE 802.3 packet, better make sure we don't
            // install a drop flow that drops everything.
            FlowKeyEthernet ethernet = key(Ethernet);
            Arrays.fill(ethernet.eth_src, EXACT_8);
            exactMatchInKey(Ethernet);
            Arrays.fill(ethernet.eth_dst, EXACT_8);
            exactMatchInKey(Ethernet);
        }
    }

    private void maskLayer2(FlowMatch fmatch, short highestLayer) {
        FlowKeyEthernet ethernet = key(Ethernet);
        FlowKeyEtherType ethertype = key(Ethertype);
        if (fmatch.isSeen(FlowMatch.Field.EthSrc)) {
            Arrays.fill(ethernet.eth_src, EXACT_8);
            exactMatchInKey(Ethernet);
        }
        if (fmatch.isSeen(FlowMatch.Field.EthDst)) {
            Arrays.fill(ethernet.eth_dst, EXACT_8);
            exactMatchInKey(Ethernet);
        }
        if (fmatch.isSeen(FlowMatch.Field.VlanId)) {
            FlowKeyVLAN vlan = key(VLan);
            vlan.vlan = EXACT_16;
            exactMatchInKey(VLan);
        }
        if (highestLayer >= 3) {
            ethertype.etherType = EXACT_16;
            exactMatchInKey(Ethertype);
            maskLayer3(fmatch, highestLayer);
        } else if (fmatch.isSeen(FlowMatch.Field.EtherType)) {
            ethertype.etherType = EXACT_16;
            exactMatchInKey(Ethertype);
        }
    }

    private void maskLayer3(FlowMatch fmatch, short highestLayer) {
        short ethertype = fmatch.getEtherType();
        if (ethertype == org.midonet.packets.IPv4.ETHERTYPE) {
            maskIPv4(fmatch, highestLayer);
            exactMatchInKey(IPv4);
        } else if (ethertype == org.midonet.packets.ARP.ETHERTYPE){
            FlowKeyARP arp = key(ARP);
            if (fmatch.isSeen(FlowMatch.Field.NetworkSrc)) {
                arp.arp_sip = EXACT_32;
            }
            if (fmatch.isSeen(FlowMatch.Field.NetworkDst)) {
                arp.arp_tip = EXACT_32;
            }
            if (fmatch.isSeen(FlowMatch.Field.NetworkProto)) {
                arp.arp_op = EXACT_8;
            }
            exactMatchInKey(ARP);
        } else if (ethertype == org.midonet.packets.IPv6.ETHERTYPE) {
            maskIPv6(fmatch, highestLayer);
            exactMatchInKey(IPv6);
        }
    }

    private void maskIPv6(FlowMatch fmatch, short highestLayer) {
        FlowKeyIPv6 ipv6 = key(IPv6);
        ipv6.ipv6_frag = EXACT_8;
        if (fmatch.isSeen(FlowMatch.Field.NetworkSrc)) {
            Arrays.fill(ipv6.ipv6_src, EXACT_32);
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkDst)) {
            Arrays.fill(ipv6.ipv6_dst, EXACT_32);
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkTOS)) {
            ipv6.ipv6_tclass = EXACT_8;
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkTTL)) {
            ipv6.ipv6_hlimit = EXACT_8;
        }
        if (highestLayer >= 4) {
            ipv6.ipv6_proto = EXACT_8;
            maskLayer4(fmatch);
        } else if (fmatch.isSeen(FlowMatch.Field.NetworkProto)) {
            ipv6.ipv6_proto = EXACT_8;
        }
    }

    private void maskIPv4(FlowMatch fmatch, short highestLayer) {
        FlowKeyIPv4 ipv4 = key(IPv4);
        ipv4.ipv4_frag = EXACT_8;
        if (fmatch.isSeen(FlowMatch.Field.NetworkSrc)) {
            ipv4.ipv4_src = EXACT_32;
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkDst)) {
            ipv4.ipv4_dst = EXACT_32;
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkTOS)) {
            ipv4.ipv4_tos = EXACT_8;
        }
        if (fmatch.isSeen(FlowMatch.Field.NetworkTTL)) {
            ipv4.ipv4_ttl = EXACT_8;
        }
        if (highestLayer >= 4) {
            ipv4.ipv4_proto = EXACT_8;
            maskLayer4(fmatch);
        } else if (fmatch.isSeen(FlowMatch.Field.NetworkProto)) {
            ipv4.ipv4_proto = EXACT_8;
        }
    }

    private void maskLayer4(FlowMatch fmatch) {
        byte proto = fmatch.getNetworkProto();
        if (proto == org.midonet.packets.UDP.PROTOCOL_NUMBER) {
            maskUdp(fmatch);
            exactMatchInKey(UDP);
        } else if (proto == org.midonet.packets.TCP.PROTOCOL_NUMBER) {
            maskTcp(fmatch);
            exactMatchInKey(TCP);
        } else if (proto == org.midonet.packets.ICMP.PROTOCOL_NUMBER) {
            maskIcmp(fmatch);
            exactMatchInKey(ICMP);
        }
    }

    private void maskIcmp(FlowMatch fmatch) {
        FlowKeyICMP icmp = key(ICMP);
        if (fmatch.isSeen(FlowMatch.Field.SrcPort)) {
            icmp.icmp_type = EXACT_8;
        }
        if (fmatch.isSeen(FlowMatch.Field.DstPort)) {
            icmp.icmp_code = EXACT_8;
        }
    }

    private void maskTcp(FlowMatch fmatch) {
        FlowKeyTCP tcp = key(TCP);
        if (fmatch.isSeen(FlowMatch.Field.SrcPort)) {
            tcp.tcp_src = EXACT_16;
        }
        if (fmatch.isSeen(FlowMatch.Field.DstPort)) {
            tcp.tcp_dst = EXACT_16;
        }
    }

    private void maskUdp(FlowMatch fmatch) {
        FlowKeyUDP udp = key(UDP);
        if (fmatch.isSeen(FlowMatch.Field.SrcPort)) {
            udp.udp_src = EXACT_16;
        }
        if (fmatch.isSeen(FlowMatch.Field.DstPort)) {
            udp.udp_dst = EXACT_16;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("FlowMask[");
        for (int i = 0; i < keys.length; ++i) {
            if (((1L << i) & keysWithExactMatch) != 0) {
                builder.append(keys[i].toString());
                builder.append(", ");
            }
        }
        builder.setLength(builder.length() - 2);
        builder.append("]");
        return builder.toString();
    }

    /**
     * Serializes the current flow mask to the specified buffer, taking
     * advantage of the fact that any omitted FlowKeys are understood
     * as being totally wildcarded.
     */
    public int serializeInto(ByteBuffer buffer) {
        int bytes = 0;
        for (int i = 0; i < keys.length; ++i) {
            if (((1L << i) & keysWithExactMatch) != 0) {
                bytes += NetlinkMessage.writeAttr(buffer, keys[i], FlowKeys.writer);
            }
        }
        return bytes;
    }

    public void use(ByteBuffer buf, short id) {
        id &= MASK;
        if (id >= keys.length)
            return;

        FlowKey flowKey = keys[id];
        if (flowKey == null)
            return;

        flowKey.deserializeFrom(buf);
        keysWithExactMatch |= 1L << id;
    }

    public static Reader<FlowMask> reader = new Reader<FlowMask>() {
        public FlowMask deserializeFrom(ByteBuffer buf) {
            FlowMask fm = new FlowMask();
            NetlinkMessage.scanAttributes(buf, fm);
            return fm;
        }
    };
}
