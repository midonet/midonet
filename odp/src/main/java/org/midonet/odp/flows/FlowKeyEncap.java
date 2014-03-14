/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

public class FlowKeyEncap implements FlowKey {

    private final List<FlowKey> keys;

    // This is used for deserialization purposes only.
    FlowKeyEncap() {
        keys = new ArrayList<>();
    }

    FlowKeyEncap(List<FlowKey> keys) {
        this.keys = keys;
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyEncap> getKey() {
        return FlowKeyAttr.ENCAP;
    }

    @Override
    public FlowKeyEncap getValue() {
        return this;
    }

    @Override
    public void serialize(Builder builder) {
        for (FlowKey key : keys) {
            builder.addAttr(key.getKey(), key.getValue());
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {

        message.iterateAttributes(new NetlinkMessage.AttributeParser() {
            @Override
            public boolean processAttribute(short attributeType, ByteBuffer buffer) {
                FlowKey flowKey = FlowKey.Builder.newInstance(attributeType);
                if (flowKey != null) {
                    flowKey.deserialize(new NetlinkMessage(buffer));
                    keys.add(flowKey);
                }

                return true;
            }
        });

        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyEncap that = (FlowKeyEncap) o;

        if (keys != null ? !keys.equals(that.keys) : that.keys != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return keys != null ? keys.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "FlowKeyEncap{keys=" + keys + '}';
    }

    public Iterable<FlowKey> getKeys() {
        return keys;
    }
}
