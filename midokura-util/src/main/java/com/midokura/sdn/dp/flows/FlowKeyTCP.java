/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.flows;

import java.nio.ByteOrder;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;

public class FlowKeyTCP implements FlowKey<FlowKeyTCP> {
    /*__be16*/ short tcp_src;
    /*__be16*/ short tcp_dst;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(tcp_src, ByteOrder.BIG_ENDIAN);
        builder.addValue(tcp_dst, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            tcp_src = message.getShort(ByteOrder.BIG_ENDIAN);
            tcp_dst = message.getShort(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyTCP> getKey() {
        return FlowKeyAttr.TCP;
    }

    @Override
    public FlowKeyTCP getValue() {
        return this;
    }


    public short getSrc() {
        return tcp_src;
    }

    public FlowKeyTCP setSrc(short src) {
        this.tcp_src = src;
        return this;
    }

    public short getDst() {
        return tcp_dst;
    }

    public FlowKeyTCP setDst(short dst) {
        this.tcp_dst = dst;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyTCP that = (FlowKeyTCP) o;

        if (tcp_dst != that.tcp_dst) return false;
        if (tcp_src != that.tcp_src) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) tcp_src;
        result = 31 * result + (int) tcp_dst;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyTCP{" +
            "tcp_src=" + tcp_src +
            ", tcp_dst=" + tcp_dst +
            '}';
    }
}
