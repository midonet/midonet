/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import java.nio.ByteOrder;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowKeyEtherType implements FlowKey<FlowKeyEtherType> {

    /* be16 */ short etherType;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(etherType);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            etherType = message.getShort(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyEtherType> getKey() {
        return FlowKeyAttr.ETHERTYPE;
    }

    @Override
    public FlowKeyEtherType getValue() {
        return this;
    }

    public short getEtherType() {
        return etherType;
    }

    public FlowKeyEtherType setEtherType(short etherType) {
        this.etherType = etherType;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEtherType that = (FlowKeyEtherType) o;

        if (etherType != that.etherType) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) etherType;
    }

    @Override
    public String toString() {
        return String.format("FlowKeyEtherType{etherType=0x%X}", etherType);
    }
}
