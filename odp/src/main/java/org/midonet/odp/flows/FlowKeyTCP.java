/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.packets.Unsigned;

public class FlowKeyTCP implements FlowKey<FlowKeyTCP> {
    /*__be16*/ int tcp_src;
    /*__be16*/ int tcp_dst;

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue((short)tcp_src, ByteOrder.BIG_ENDIAN);
        builder.addValue((short)tcp_dst, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            tcp_src = Unsigned.unsign(message.getShort(ByteOrder.BIG_ENDIAN));
            tcp_dst = Unsigned.unsign(message.getShort(ByteOrder.BIG_ENDIAN));
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


    public int getSrc() {
        return tcp_src;
    }

    public FlowKeyTCP setSrc(int src) {
        if (src < 0 || src > 0xffff)
            throw new IllegalArgumentException("TCP port out of range");
        this.tcp_src = src;
        return this;
    }

    public int getDst() {
        return tcp_dst;
    }

    public FlowKeyTCP setDst(int dst) {
        if (dst < 0 || dst > 0xffff)
            throw new IllegalArgumentException("TCP port out of range");
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
        int result = tcp_src;
        result = 31 * result + tcp_dst;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyTCP{tcp_src=" + tcp_src + ", tcp_dst=" + tcp_dst + '}';
    }
}
