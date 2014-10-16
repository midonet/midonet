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

import java.util.Arrays;
import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.Net;

public class FlowKeyIPv6 implements FlowKey {

    /*__be32*/ private int[] ipv6_src = new int[4];
    /*__be32*/ private int[] ipv6_dst = new int[4];
    /*__be32*/ private int ipv6_label;    /* 20-bits in least-significant bits. */
    /*__u8*/ private byte ipv6_proto;
    /*__u8*/ private byte ipv6_tclass;
    /*__u8*/ private byte ipv6_hlimit;
    /*__u8*/ private byte ipv6_frag;    /* One of OVS_FRAG_TYPE_*. */

    private int hashCode = 0;
    private int connectionHash = 0;

    // This is used for deserialization purposes only.
    FlowKeyIPv6() { }

    FlowKeyIPv6(IPv6Addr source, IPv6Addr destination, byte protocol,
                byte hlimit, byte fragmentType) {
        ipv6_src = toIntArray(source);
        ipv6_dst = toIntArray(destination);
        ipv6_proto = protocol;
        ipv6_hlimit = hlimit;
        ipv6_frag = fragmentType;
        computeHashCode();
    }

    public FlowKeyIPv6(int[] source, int[] destination, byte protocol,
                       byte hlimit, byte fragmentType) {
        ipv6_src = source;
        ipv6_dst = destination;
        ipv6_proto = protocol;
        ipv6_hlimit = hlimit;
        ipv6_frag = fragmentType;
    }

    private static int[] toIntArray(IPv6Addr addr) {
        return new int[] {
            (int)(addr.upperWord() >>> 32),
            (int)(addr.upperWord()),
            (int)(addr.lowerWord() >>> 32),
            (int)(addr.lowerWord())
        };
    }

    public int serializeInto(ByteBuffer buffer) {
        BytesUtil.instance.writeBEIntsInto(buffer, ipv6_src);
        BytesUtil.instance.writeBEIntsInto(buffer, ipv6_dst);
        buffer.putInt(BytesUtil.instance.reverseBE(ipv6_label));
        buffer.put(ipv6_proto);
        buffer.put(ipv6_tclass);
        buffer.put(ipv6_hlimit);
        buffer.put(ipv6_frag);
        return 40;
    }

    public void deserializeFrom(ByteBuffer buf) {
        BytesUtil.instance.readBEIntsFrom(buf, ipv6_src);
        BytesUtil.instance.readBEIntsFrom(buf, ipv6_dst);
        ipv6_label = BytesUtil.instance.reverseBE(buf.getInt());
        ipv6_proto = buf.get();
        ipv6_tclass = buf.get();
        ipv6_hlimit = buf.get();
        ipv6_frag = buf.get();
        computeHashCode();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.IPv6;
    }

    public int[] getSrc() {
        return ipv6_src;
    }

    public int[] getDst() {
        return ipv6_dst;
    }

    public int getLabel() {
        return ipv6_label;
    }
    public byte getProto() {
        return ipv6_proto;
    }

    public byte getTClass() {
        return ipv6_tclass;
    }

    public byte getHLimit() {
        return ipv6_hlimit;
    }

    public IPFragmentType getFrag() {
        return IPFragmentType.fromByte(ipv6_frag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyIPv6 that = (FlowKeyIPv6) o;

        return (ipv6_frag == that.ipv6_frag)
            && (ipv6_hlimit == that.ipv6_hlimit)
            && (ipv6_label == that.ipv6_label)
            && (ipv6_proto == that.ipv6_proto)
            && (ipv6_tclass == that.ipv6_tclass)
            && Arrays.equals(ipv6_dst, that.ipv6_dst)
            && Arrays.equals(ipv6_src, that.ipv6_src);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public void computeHashCode() {
        hashCode = Arrays.hashCode(ipv6_src);
        hashCode = 31 * hashCode + Arrays.hashCode(ipv6_dst);
        hashCode = 31 * hashCode + ipv6_label;
        hashCode = 31 * hashCode + (int) ipv6_proto;
        hashCode = 31 * hashCode + (int) ipv6_tclass;
        hashCode = 31 * hashCode + (int) ipv6_hlimit;
        hashCode = 31 * hashCode + (int) ipv6_frag;
        computeConnectionHash();
    }

    @Override
    public int connectionHash() {
        return connectionHash;
    }

    public void computeConnectionHash() {
        connectionHash =  Arrays.hashCode(ipv6_src);
        connectionHash = 31 * connectionHash + Arrays.hashCode(ipv6_dst);
        connectionHash = 31 * connectionHash + (int) ipv6_proto;
    }

    @Override
    public String toString() {
        return "IPv6{" +
              "src=" + Net.convertIPv6BytesToString(ipv6_src) +
            ", dst=" + Net.convertIPv6BytesToString(ipv6_dst) +
            ", label=" + ipv6_label +
            ", proto=" + ipv6_proto +
            ", tclass=" + ipv6_tclass +
            ", hlimit=" + ipv6_hlimit +
            ", frag=" + ipv6_frag +
            '}';
    }
}
