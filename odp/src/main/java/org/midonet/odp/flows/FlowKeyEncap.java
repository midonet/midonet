/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

public class FlowKeyEncap implements FlowKey<FlowKeyEncap> {

    List<FlowKey<?>> keys = new ArrayList<FlowKey<?>>();

    @Override
    public NetlinkMessage.AttrKey<FlowKeyEncap> getKey() {
        return FlowKeyAttr.ENCAP;
    }

    @Override
    public FlowKeyEncap getValue() {
        return this;
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addAttrs(keys);
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
        return "FlowKeyEncap{" +
            "keys=" + keys +
            '}';
    }

    public List<FlowKey<?>> getKeys() {
        return keys;
    }

    public FlowKeyEncap setKeys(List<FlowKey<?>> keys) {
        this.keys = keys;
        return this;
    }

    public FlowKeyEncap addKey(FlowKey<?> key) {
        keys.add(key);
        return this;
    }
}
