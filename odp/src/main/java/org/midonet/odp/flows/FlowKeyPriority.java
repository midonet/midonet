/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;

public class FlowKeyPriority implements FlowKey {

    /*__u32*/ private int priority;

    // This is used for deserialization purposes only.
    FlowKeyPriority() { }

    FlowKeyPriority(int priority) {
        this.priority = priority;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(priority);
        return 4;
    }

    public boolean deserialize(NetlinkMessage message) {
        try {
            priority = message.getInt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return FlowKeyAttr.PRIORITY.getId();
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyPriority that = (FlowKeyPriority) o;

        return priority == that.priority;
    }

    @Override
    public int hashCode() {
        return priority;
    }

    @Override
    public String toString() {
        return "FlowKeyPriority{priority=" + priority + '}';
    }
}
