/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.Net;

import java.nio.ByteOrder;
import java.util.Arrays;

public class FlowKeyIPv6 implements FlowKey {

    /*__be32*/ private int[] ipv6_src = new int[4];
    /*__be32*/ private int[] ipv6_dst = new int[4];
    /*__be32*/ private int ipv6_label;    /* 20-bits in least-significant bits. */
    /*__u8*/ private byte ipv6_proto;
    /*__u8*/ private byte ipv6_tclass;
    /*__u8*/ private byte ipv6_hlimit;
    /*__u8*/ private byte ipv6_frag;    /* One of OVS_FRAG_TYPE_*. */

    // This is used for deserialization purposes only.
    FlowKeyIPv6() { }

    FlowKeyIPv6(IPv6Addr source, IPv6Addr destination, byte protocol,
                byte hlimit, byte fragmentType) {
        ipv6_src = toIntArray(source);
        ipv6_dst = toIntArray(destination);
        ipv6_proto = protocol;
        ipv6_hlimit = hlimit;
        ipv6_frag = fragmentType;
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

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(ipv6_src, ByteOrder.BIG_ENDIAN)
               .addValue(ipv6_dst, ByteOrder.BIG_ENDIAN)
               .addValue(ipv6_label, ByteOrder.BIG_ENDIAN)
               .addValue(ipv6_proto)
               .addValue(ipv6_tclass)
               .addValue(ipv6_hlimit)
               .addValue(ipv6_frag);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            message.getInts(ipv6_src, ByteOrder.BIG_ENDIAN);
            message.getInts(ipv6_dst, ByteOrder.BIG_ENDIAN);
            ipv6_label = message.getInt(ByteOrder.BIG_ENDIAN);
            ipv6_proto = message.getByte();
            ipv6_tclass = message.getByte();
            ipv6_hlimit = message.getByte();
            ipv6_frag = message.getByte();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyIPv6> getKey() {
        return FlowKeyAttr.IPv6;
    }

    @Override
    public FlowKeyIPv6 getValue() {
        return this;
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

        FlowKeyIPv6 that = (FlowKeyIPv6) o;

        if (ipv6_frag != that.ipv6_frag) return false;
        if (ipv6_hlimit != that.ipv6_hlimit) return false;
        if (ipv6_label != that.ipv6_label) return false;
        if (ipv6_proto != that.ipv6_proto) return false;
        if (ipv6_tclass != that.ipv6_tclass) return false;
        if (!Arrays.equals(ipv6_dst, that.ipv6_dst)) return false;
        if (!Arrays.equals(ipv6_src, that.ipv6_src)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = ipv6_src != null ? Arrays.hashCode(ipv6_src) : 0;
        result = 31 * result + (ipv6_dst != null ? Arrays.hashCode(
            ipv6_dst) : 0);
        result = 31 * result + ipv6_label;
        result = 31 * result + (int) ipv6_proto;
        result = 31 * result + (int) ipv6_tclass;
        result = 31 * result + (int) ipv6_hlimit;
        result = 31 * result + (int) ipv6_frag;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyIPv6{" +
            "ipv6_src=" + Net.convertIPv6BytesToString(ipv6_src) +
            ", ipv6_dst=" + Net.convertIPv6BytesToString(ipv6_dst) +
            ", ipv6_label=" + ipv6_label +
            ", ipv6_proto=" + ipv6_proto +
            ", ipv6_tclass=" + ipv6_tclass +
            ", ipv6_hlimit=" + ipv6_hlimit +
            ", ipv6_frag=" + ipv6_frag +
            '}';
    }
}
