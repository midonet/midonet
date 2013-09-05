/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;
import java.util.Arrays;

import org.midonet.packets.IPv6Addr;
import org.midonet.packets.Net;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;


public class FlowKeyIPv6 implements FlowKey<FlowKeyIPv6> {

    /*__be32*/ int[] ipv6_src = new int[4];
    /*__be32*/ int[] ipv6_dst = new int[4];
    /*__be32*/ int ipv6_label;    /* 20-bits in least-significant bits. */
    /*__u8*/ byte ipv6_proto;
    /*__u8*/ byte ipv6_tclass;
    /*__u8*/ byte ipv6_hlimit;
    /*__u8*/ byte ipv6_frag;    /* One of OVS_FRAG_TYPE_*. */

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

    public FlowKeyIPv6 setSrc(int[] src) {
        this.ipv6_src = src;
        return this;
    }

    public FlowKeyIPv6 setSrc(IPv6Addr src) {
        int a[] = { (int)(src.upperWord() >>> 32),
                    (int)(src.upperWord() & 0xFFFFFFFF),
                    (int)(src.lowerWord() >>> 32),
                    (int)(src.lowerWord() & 0xFFFFFFFF)
                  };
        return setSrc(a);
    }

    public int[] getDst() {
        return ipv6_dst;
    }

    public FlowKeyIPv6 setDst(int[] dst) {
        this.ipv6_dst = dst;
        return this;
    }

    public FlowKeyIPv6 setDst(IPv6Addr dst) {
        int a[] = { (int)(dst.upperWord() >>> 32),
                    (int)(dst.upperWord() & 0xFFFFFFFF),
                    (int)(dst.lowerWord() >>> 32),
                    (int)(dst.lowerWord() & 0xFFFFFFFF)
                  };
        return setDst(a);
    }

    public int getLabel() {
        return ipv6_label;
    }

    public FlowKeyIPv6 setLabel(int label) {
        this.ipv6_label = label;
        return this;
    }

    public byte getProto() {
        return ipv6_proto;
    }

    public FlowKeyIPv6 setProto(byte proto) {
        this.ipv6_proto = proto;
        return this;
    }

    public byte getTClass() {
        return ipv6_tclass;
    }

    public FlowKeyIPv6 setTClass(byte tClass) {
        this.ipv6_tclass = tClass;
        return this;
    }

    public byte getHLimit() {
        return ipv6_hlimit;
    }

    public FlowKeyIPv6 setHLimit(byte hLimit) {
        this.ipv6_hlimit = hLimit;
        return this;
    }

    public IPFragmentType getFrag() {
        return IPFragmentType.fromByte(ipv6_frag);
    }

    public FlowKeyIPv6 setFrag(IPFragmentType fragmentType) {
        this.ipv6_frag = fragmentType.value;
        return this;
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
