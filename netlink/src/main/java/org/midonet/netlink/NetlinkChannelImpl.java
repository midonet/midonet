/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.channels.spi.SelectorProvider;

import sun.nio.ch.SelChImpl;

/**
 * Specialization of a NetlinkChannel that extends SelChImpl to work
 * as a SelectableChannel within sun implementation of java.nio.
 */
class NetlinkChannelImpl extends NetlinkChannel implements SelChImpl {

    public NetlinkChannelImpl(SelectorProvider provider,
                              NetlinkProtocol protocol) {
        super(provider, protocol);
    }
}
