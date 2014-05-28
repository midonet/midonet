/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.midonet.netlink.NetlinkMessage;

public class FlowKeyEncap implements FlowKey {

    private final List<FlowKey> keys;

    // This is used for deserialization purposes only.
    FlowKeyEncap() {
        keys = new ArrayList<>();
    }

    FlowKeyEncap(List<FlowKey> keys) {
        this.keys = keys;
    }

    public short attrId() {
        return FlowKeyAttr.ENCAP.getId();
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;
        for (FlowKey key : keys) {
            nBytes += NetlinkMessage.writeAttr(buffer, key, FlowKey.translator);
        }
        return nBytes;
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
