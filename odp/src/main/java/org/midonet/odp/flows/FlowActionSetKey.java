/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowActionSetKey implements FlowAction {

    FlowKey<?> flowKey;

    // This is used for deserialization purposes only.
    FlowActionSetKey() { }

    FlowActionSetKey(FlowKey<?> flowKey) {
        this.flowKey = flowKey;
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addAttr(flowKey.getKey(), flowKey);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {

        message.iterateAttributes(new NetlinkMessage.AttributeParser() {
            @Override
            public boolean processAttribute(short attributeType, ByteBuffer buffer) {
                flowKey = FlowKey.Builder.newInstance(attributeType);
                if (flowKey != null) {
                    flowKey.deserialize(new NetlinkMessage(buffer));
                }

                return flowKey == null;
            }
        });

        return flowKey != null;
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionSetKey> getKey() {
        return FlowActionAttr.SET;
    }

    @Override
    public FlowActionSetKey getValue() {
        return this;
    }

    public FlowKey<?> getFlowKey() {
        return flowKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowActionSetKey that = (FlowActionSetKey) o;

        if (flowKey != null ? !flowKey.equals(
            that.flowKey) : that.flowKey != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return flowKey != null ? flowKey.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "FlowActionSetKey{" +
            "flowKey=" + flowKey +
            '}';
    }
}
