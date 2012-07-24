/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.messages;

import com.midokura.netlink.NetlinkMessage;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface BuilderAware {

    public void serialize(BaseBuilder builder);

    public boolean deserialize(NetlinkMessage message);
}
