/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.odp.OpenVSwitch;

import static org.midonet.packets.Unsigned.unsign;

public class FlowKeyICMPv6 implements FlowKey {

    /*__u8*/ private byte icmpv6_type;
    /*__u8*/ private byte icmpv6_code;

    // This is used for deserialization purposes only.
    FlowKeyICMPv6() { }

    FlowKeyICMPv6(byte type, byte code) {
        this.icmpv6_type = type;
        this.icmpv6_code = code;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.put(icmpv6_type);
        buffer.put(icmpv6_code);
        return 2;
    }

    public void deserializeFrom(ByteBuffer buf) {
        icmpv6_type = buf.get();
        icmpv6_code = buf.get();
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.ICMPv6;
    }

    public byte getType() {
        return icmpv6_type;
    }

    public byte getCode() {
        return icmpv6_code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyICMPv6 that = (FlowKeyICMPv6) o;

        return (icmpv6_code == that.icmpv6_code)
            && (icmpv6_type == that.icmpv6_type);
    }

    @Override
    public int hashCode() {
        return 31 * ((int)icmpv6_type) + (int) icmpv6_code;
    }

    @Override
    public int connectionHash() {
        return hashCode();
    }

    @Override
    public String toString() {
        return "FlowKeyICMPv6{" +
            "icmpv6_type=" + unsign(icmpv6_type) +
            ", icmpv6_code=" + unsign(icmpv6_code) +
            '}';
    }
}
