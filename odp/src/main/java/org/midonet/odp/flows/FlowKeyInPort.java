/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

public class FlowKeyInPort implements CachedFlowKey {

    /*__u32*/ private int portNo;

    // This is used for deserialization purposes only.
    FlowKeyInPort() { }

    FlowKeyInPort(int portNo) {
        this.portNo = portNo;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(portNo);
        return 4;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(portNo);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            portNo = message.getInt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyInPort> getKey() {
        return FlowKeyAttr.IN_PORT;
    }

    public short attrId() {
        return FlowKeyAttr.IN_PORT.getId();
    }

    @Override
    public FlowKeyInPort getValue() {
        return this;
    }

    public int getInPort() {
        return this.portNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyInPort that = (FlowKeyInPort) o;

        if (portNo != that.portNo) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return portNo;
    }

    @Override
    public String toString() {
        return "FlowKeyInPort{portNo=" + portNo + '}';
    }
}
