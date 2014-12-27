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
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

/**
 * Flow key/mask for IP
 *
 * When using IP masks, the flow must also include an exact match for the
 * ethernet protocol equal to IP. Otherwise, no packets will match this key/mask.
 *
 * Example:
 *
 * <code>
 * FlowMatch flowMatch = new FlowMatch();
 * FlowMask flowMask = new FlowMask();
 *
 * // ... some other matches/masks
 *
 * flowMatch.addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP));
 * flowMask.addKey(FlowKeys.etherType(0xFFFF.toShort));
 * flowMatch.addKey(FlowKeys.ipv4(srcIp, dstIp, IpProtocol.TCP));
 * flowMask.addKey(FlowKeys.ipv4(0x0001.toShort, 0x0001.toShort, 0x00, 0x00, 0x00, 0x00));
 *
 * // ... some other matches/masks
 *
 * Flow flow = new Flow(flowMatch, flowMask);
 * </code>
 *
 * @see org.midonet.odp.flows.FlowKey
 * @see org.midonet.odp.flows.FlowKeyEthernet
 * @see org.midonet.odp.FlowMask
 */
public class FlowKeyIPv4 implements FlowKey {

    /*__be32*/ public int ipv4_src;
    /*__be32*/ public int ipv4_dst;
    /*__u8*/ public byte ipv4_proto;
    /*__u8*/ public byte ipv4_tos;
    /*__u8*/ public byte ipv4_ttl;
    /*__u8*/ public byte ipv4_frag;    /* One of OVS_FRAG_TYPE_*. */

    // This is used for deserialization purposes only.
    FlowKeyIPv4() { }

    public FlowKeyIPv4(int src, int dst, byte protocol, byte typeOfService,
                       byte ttl, byte fragmentType) {
        this.ipv4_src = src;
        this.ipv4_dst = dst;
        this.ipv4_proto = protocol;
        this.ipv4_tos = typeOfService;
        this.ipv4_ttl = ttl;
        this.ipv4_frag = fragmentType;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_src));
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_dst));
        buffer.put(ipv4_proto);
        buffer.put(ipv4_tos);
        buffer.put(ipv4_ttl);
        buffer.put(ipv4_frag);
        return 12;
    }

    public void deserializeFrom(ByteBuffer buf) {
        ipv4_src = BytesUtil.instance.reverseBE(buf.getInt());
        ipv4_dst = BytesUtil.instance.reverseBE(buf.getInt());
        ipv4_proto = buf.get();
        ipv4_tos = buf.get();
        ipv4_ttl = buf.get();
        ipv4_frag = buf.get();
    }

    @Override
    public void wildcard() {
        ipv4_src = 0;
        ipv4_dst = 0;
        ipv4_proto = 0;
        ipv4_tos = 0;
        ipv4_ttl = 0;
        ipv4_frag = 0;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.IPv4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyIPv4 that = (FlowKeyIPv4) o;

        return (ipv4_dst == that.ipv4_dst)
            && (ipv4_frag == that.ipv4_frag)
            && (ipv4_proto == that.ipv4_proto)
            && (ipv4_src == that.ipv4_src)
            && (ipv4_tos == that.ipv4_tos)
            && (ipv4_ttl == that.ipv4_ttl);
    }

    @Override
    public int hashCode() {
        int hashCode = ipv4_src;
        hashCode = 31 * hashCode + ipv4_dst;
        hashCode = 31 * hashCode + (int) ipv4_proto;
        hashCode = 31 * hashCode + (int) ipv4_tos;
        hashCode = 31 * hashCode + (int) ipv4_ttl;
        hashCode = 31 * hashCode + (int) ipv4_frag;
        return hashCode;
    }

    @Override
    public String toString() {
        return "KeyIPv4{" +
              "src=" + IPv4Addr.intToString(ipv4_src) +
            ", dst=" + IPv4Addr.intToString(ipv4_dst) +
            ", proto=" + ipv4_proto +
            ", tos=" + ipv4_tos +
            ", ttl=" + ipv4_ttl +
            ", frag=" + ipv4_frag +
            '}';
    }
}
