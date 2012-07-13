/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionUserspace implements FlowAction<FlowActionUserspace> {

    @Override
    public void serialize(BaseBuilder builder) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionUserspace> getKey() {
        return FlowActionKey.USERSPACE;
    }

    @Override
    public FlowActionUserspace getValue() {
        return this;
    }
}
