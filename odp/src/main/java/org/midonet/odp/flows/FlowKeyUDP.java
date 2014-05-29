/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;

public class FlowKeyUDP implements FlowKey {
    /*__be16*/ private int udp_src;
    /*__be16*/ private int udp_dst;

    // This is used for deserialization purposes only.
    FlowKeyUDP() { }

    FlowKeyUDP(int source, int destination) {
        TCP.ensurePortInRange(source);
        TCP.ensurePortInRange(destination);
        udp_src = source;
        udp_dst = destination;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue((short)udp_src, ByteOrder.BIG_ENDIAN);
        builder.addValue((short)udp_dst, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            udp_src = Unsigned.unsign(message.getShort(ByteOrder.BIG_ENDIAN));
            udp_dst = Unsigned.unsign(message.getShort(ByteOrder.BIG_ENDIAN));
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

    public int getUdpSrc() {
        return udp_src;
    }

    public int getUdpDst() {
        return udp_dst;
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
        int result = udp_src;
        result = 31 * result + udp_dst;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyUDP{udp_src=" + udp_src + ", udp_dst=" + udp_dst + "}";
    }
}
