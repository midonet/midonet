/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.List;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;
import org.midonet.packets.Unsigned;

public class FlowKeyTCPFlags implements FlowKey {

    /*__be16*/ private short flags;

    // This is used for deserialization purposes only.
    FlowKeyTCPFlags() { }

    FlowKeyTCPFlags(short f) {
        flags = f;
    }

    FlowKeyTCPFlags(List<TCP.Flag> flst) {
        flags = TCP.Flag.allOf(flst);
    }

    public short getFlags() {
        return flags;
    }

    // TODO: probably we do not need these two functions... [alvaro]

    public boolean getFlag(TCP.Flag f) { return (flags & f.bit) != 0; }

    public void setFlag(TCP.Flag f, boolean v) {
        if (v)
            flags |= f.bit;
        else
            flags &= ~f.bit;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putShort(BytesUtil.instance.reverseBE((short)flags));
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        flags = (short) Unsigned.unsign(BytesUtil.instance.reverseBE(buf.getShort()));
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.TcpFlags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyTCPFlags that = (FlowKeyTCPFlags) o;

        return (flags == that.flags);
    }

    @Override
    public int hashCode() {
        return flags;
    }

    @Override
    public int connectionHash() {
        return 0;
    }

    @Override
    public String toString() {
        return "FlowKeyTCPFlags{flags='" +
               TCP.Flag.allOfToString(flags) + "'}";
    }
}

