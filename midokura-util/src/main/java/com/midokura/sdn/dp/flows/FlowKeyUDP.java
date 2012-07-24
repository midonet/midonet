/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.flows;

import java.nio.ByteOrder;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;

public class FlowKeyUDP implements FlowKey<FlowKeyUDP> {
    /*__be16*/ short udp_src;
    /*__be16*/ short udp_dst;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(udp_src, ByteOrder.BIG_ENDIAN);
        builder.addValue(udp_dst, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            udp_src = message.getShort(ByteOrder.BIG_ENDIAN);
            udp_dst = message.getShort(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyUDP> getKey() {
        return FlowKeyAttr.UDP;
    }

    @Override
    public FlowKeyUDP getValue() {
        return this;
    }

    public short getUdpSrc() {
        return udp_src;
    }

    public FlowKeyUDP setUdpSrc(short udpSrc) {
        this.udp_src = udpSrc;
        return this;
    }

    public short getUdpDst() {
        return udp_dst;
    }

    public FlowKeyUDP setUdpDst(short udpDst) {
        this.udp_dst = udpDst;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyUDP that = (FlowKeyUDP) o;

        if (udp_dst != that.udp_dst) return false;
        if (udp_src != that.udp_src) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) udp_src;
        result = 31 * result + (int) udp_dst;
        return result;
    }

    @Override
    public String toString() {
        return String.format("FlowKeyUDP{udp_src=0x%X, udp_dst=0x%X}",
                             udp_src, udp_dst);
    }
}
