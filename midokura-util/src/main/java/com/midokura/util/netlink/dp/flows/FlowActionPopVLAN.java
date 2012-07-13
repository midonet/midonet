/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionPopVLAN implements FlowAction<FlowActionPopVLAN> {

    @Override
    public void serialize(BaseBuilder builder) {
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        return true;
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionPopVLAN> getKey() {
        return FlowActionKey.POP_VLAN;
    }

    @Override
    public FlowActionPopVLAN getValue() {
        return this;
    }
}
