/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;

public class FlowKeyEncap implements FlowKey, Randomize, AttributeHandler {

    private List<FlowKey> keys;

    // This is used for deserialization purposes only.
    FlowKeyEncap() {
        keys = new ArrayList<>();
    }

    FlowKeyEncap(List<FlowKey> keys) {
        this.keys = keys;
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.Encap;
    }

    public int serializeInto(ByteBuffer buffer) {
        int nBytes = 0;
        for (FlowKey key : keys) {
            nBytes += NetlinkMessage.writeAttr(buffer, key, FlowKeys.writer);
        }
        return nBytes;
    }

    public void deserializeFrom(ByteBuffer buf) {
        NetlinkMessage.scanAttributes(buf, this);
    }

    public void use(ByteBuffer buf, short id) {
        FlowKey key = FlowKeys.newBlankInstance(id);
        if (key == null)
            return;
        key.deserializeFrom(buf);
        keys.add(key);
    }

    public void randomize() {
        keys = FlowKeys.randomKeys();
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
