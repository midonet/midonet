/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionSetKey implements FlowAction<FlowActionSetKey> {

    FlowKey flowKey;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addAttr(flowKey.getKey(), flowKey);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        return false;
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionSetKey> getKey() {
        return FlowActionKey.SET;
    }

    @Override
    public FlowActionSetKey getValue() {
        return this;
    }

    public FlowKey getFlowKey() {
        return flowKey;
    }

    public FlowActionSetKey setFlowKey(FlowKey flowKey) {
        this.flowKey = flowKey;
        return this;
    }

}
