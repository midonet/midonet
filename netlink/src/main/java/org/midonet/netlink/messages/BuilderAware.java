/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.messages;

import org.midonet.netlink.NetlinkMessage;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface BuilderAware {
    public boolean deserialize(NetlinkMessage message);
}
