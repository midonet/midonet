/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.messages;

import java.nio.ByteBuffer;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface BuilderAware {
    public boolean deserialize(ByteBuffer buffer);
}
