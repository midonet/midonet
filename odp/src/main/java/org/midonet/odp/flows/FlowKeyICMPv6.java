/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;


import static org.midonet.packets.Unsigned.unsign;

public class FlowKeyICMPv6 implements FlowKey<FlowKeyICMPv6> {
    /*__u8*/ private byte icmpv6_type;
    /*__u8*/ private byte icmpv6_code;

    // This is used for deserialization purposes only.
    FlowKeyICMPv6() { }

    FlowKeyICMPv6(byte type, byte code) {
        this.icmpv6_type = type;
        this.icmpv6_code = code;
    }

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(icmpv6_type);
        builder.addValue(icmpv6_code);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            icmpv6_type = message.getByte();
            icmpv6_code = message.getByte();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyICMPv6> getKey() {
        return FlowKeyAttr.ICMPv6;
    }

    @Override
    public FlowKeyICMPv6 getValue() {
        return this;
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

        FlowKeyICMPv6 that = (FlowKeyICMPv6) o;

        if (icmpv6_code != that.icmpv6_code) return false;
        if (icmpv6_type != that.icmpv6_type) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) icmpv6_type;
        result = 31 * result + (int) icmpv6_code;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyICMPv6{" +
            "icmpv6_type=" + unsign(icmpv6_type) +
            ", icmpv6_code=" + unsign(icmpv6_code) +
            '}';
    }
}
