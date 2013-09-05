/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.Net;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;


public class FlowKeyIPv4 implements FlowKey<FlowKeyIPv4> {
    /*__be32*/ int ipv4_src;
    /*__be32*/ int ipv4_dst;
    /*__u8*/ byte ipv4_proto;
    /*__u8*/ byte ipv4_tos;
    /*__u8*/ byte ipv4_ttl;
    /*__u8*/ byte ipv4_frag;    /* One of OVS_FRAG_TYPE_*. */

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(ipv4_src, ByteOrder.BIG_ENDIAN)
               .addValue(ipv4_dst, ByteOrder.BIG_ENDIAN)
               .addValue(ipv4_proto)
               .addValue(ipv4_tos)
               .addValue(ipv4_ttl)
               .addValue(ipv4_frag);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            ipv4_src = message.getInt(ByteOrder.BIG_ENDIAN);
            ipv4_dst = message.getInt(ByteOrder.BIG_ENDIAN);
            ipv4_proto = message.getByte();
            ipv4_tos = message.getByte();
            ipv4_ttl = message.getByte();
            ipv4_frag = message.getByte();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyIPv4> getKey() {
        return FlowKeyAttr.IPv4;
    }

    @Override
    public FlowKeyIPv4 getValue() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyIPv4 that = (FlowKeyIPv4) o;

        if (ipv4_dst != that.ipv4_dst) return false;
        if (ipv4_frag != that.ipv4_frag) return false;
        if (ipv4_proto != that.ipv4_proto) return false;
        if (ipv4_src != that.ipv4_src) return false;
        if (ipv4_tos != that.ipv4_tos) return false;
        if (ipv4_ttl != that.ipv4_ttl) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = ipv4_src;
        result = 31 * result + ipv4_dst;
        result = 31 * result + (int) ipv4_proto;
        result = 31 * result + (int) ipv4_tos;
        result = 31 * result + (int) ipv4_ttl;
        result = 31 * result + (int) ipv4_frag;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyIPv4{" +
            "ipv4_src=" + Net.convertIntAddressToString(ipv4_src) +
            ", ipv4_dst=" + Net.convertIntAddressToString(ipv4_dst) +
            ", ipv4_proto=" + ipv4_proto +
            ", ipv4_tos=" + ipv4_tos +
            ", ipv4_ttl=" + ipv4_ttl +
            ", ipv4_frag=" + ipv4_frag +
            '}';
    }

    public int getSrc() {
        return ipv4_src;
    }

    public FlowKeyIPv4 setSrc(int src) {
        this.ipv4_src = src;
        return this;
    }

    public FlowKeyIPv4 setSrc(IPv4Addr src) {
        this.ipv4_src = src.toInt();
        return this;
    }

    public int getDst() {
        return ipv4_dst;
    }

    public FlowKeyIPv4 setDst(int dst) {
        this.ipv4_dst = dst;
        return this;
    }

    public FlowKeyIPv4 setDst(IPv4Addr dst) {
        this.ipv4_dst = dst.toInt();
        return this;
    }

    public byte getProto() {
        return ipv4_proto;
    }

    public FlowKeyIPv4 setProto(byte proto) {
        this.ipv4_proto = proto;
        return this;
    }

    public byte getTos() {
        return ipv4_tos;
    }

    public FlowKeyIPv4 setTos(byte tos) {
        this.ipv4_tos = tos;
        return this;
    }

    public byte getTtl() {
        return ipv4_ttl;
    }

    public FlowKeyIPv4 setTtl(byte ttl) {
        this.ipv4_ttl = ttl;
        return this;
    }

    public byte getFrag() {
        return ipv4_frag;
    }

    public FlowKeyIPv4 setFrag(byte frag) {
        this.ipv4_frag = frag;
        return this;
    }
}

