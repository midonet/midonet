/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowKeyPriority implements FlowKey<FlowKeyPriority> {

    /*__be32*/ int priority;

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addValue(priority);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            priority = message.getInt(ByteOrder.BIG_ENDIAN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyPriority> getKey() {
        return FlowKeyAttr.PRIORITY;
    }

    @Override
    public FlowKeyPriority getValue() {
        return this;
    }

    public int getPriority() {
        return priority;
    }

    public FlowKeyPriority setPriority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyPriority that = (FlowKeyPriority) o;

        if (priority != that.priority) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return priority;
    }

    @Override
    public String toString() {
        return "FlowKeyPriority{" +
            "priority=" + priority +
            '}';
    }
}
