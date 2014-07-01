/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;

public class FlowKeyTCP implements FlowKey {
    /*__be16*/ private int tcp_src;
    /*__be16*/ private int tcp_dst;

    // This is used for deserialization purposes only.
    FlowKeyTCP() { }

    FlowKeyTCP(int source, int destination) {
        TCP.ensurePortInRange(source);
        TCP.ensurePortInRange(destination);
        tcp_src = source;
        tcp_dst = destination;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)tcp_src));
        buffer.putShort(BytesUtil.instance.reverseBE((short)tcp_dst));
        return 4;
    }

    public void deserializeFrom(ByteBuffer buf) {
        tcp_src = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
        tcp_dst = Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.TCP;
    }

    public int getSrc() {
        return tcp_src;
    }

    public int getDst() {
        return tcp_dst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyTCP that = (FlowKeyTCP) o;

        return (tcp_dst == that.tcp_dst) && (tcp_src == that.tcp_src);
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
