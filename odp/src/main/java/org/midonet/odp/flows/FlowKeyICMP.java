/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

public class FlowKeyICMP implements FlowKey {
    /*__u8*/ protected byte icmp_type;
    /*__u8*/ protected byte icmp_code;

    // This is used for deserialization purposes only.
    FlowKeyICMP() { }

    FlowKeyICMP(byte type, byte code) {
        icmp_type = type;
        icmp_code = code;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.put(icmp_type);
        buffer.put(icmp_code);
        return 2;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(icmp_type);
        builder.addValue(icmp_code);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            icmp_type = message.getByte();
            icmp_code = message.getByte();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyICMP> getKey() {
        return FlowKeyAttr.ICMP;
    }

    public short attrId() {
        return FlowKeyAttr.ICMP.getId();
    }

    @Override
    public FlowKeyICMP getValue() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyICMP that = (FlowKeyICMP) o;

        if (icmp_code != that.icmp_code) return false;
        if (icmp_type != that.icmp_type) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = icmp_type;
        result = 31 * result + icmp_code;
        return result;
    }

    @Override
    public String toString() {
        return "FlowKeyICMP{icmp_type=0x"+Integer.toHexString(icmp_type) +
                         ", icmp_code=" + icmp_code + "}";
    }

    public byte getType() {
        return icmp_type;
    }

    public byte getCode() {
        return icmp_code;
    }
}
