/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
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

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)udp_src));
        buffer.putShort(BytesUtil.instance.reverseBE((short)udp_dst));
        return 4;
    }

    @Override
    public boolean deserialize(ByteBuffer buf) {
        try {
            udp_src = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
            udp_dst = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.UDP;
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

        @SuppressWarnings("unchecked")
        FlowKeyUDP that = (FlowKeyUDP) o;

        return (udp_dst == that.udp_dst) && (udp_src == that.udp_src);
    }

    @Override
    public int hashCode() {
        return 31 * udp_src + udp_dst;
    }

    @Override
    public String toString() {
        return "FlowKeyUDP{udp_src=" + udp_src + ", udp_dst=" + udp_dst + "}";
    }
}
