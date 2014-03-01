/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.channels.spi.SelectorProvider;

import sun.nio.ch.SelChImpl;

/**
 * Specialization of a UnixDomainChannel that extends SelChImpl to
 * work as a SelectableChannel within sun implementation of java.nio.
 */
class UnixDomainChannelImpl extends UnixDomainChannel implements SelChImpl {

    public UnixDomainChannelImpl(SelectorProvider provider,
                                 AfUnix.Type sockType) {
        super(provider, sockType);
    }

    public UnixDomainChannelImpl(SelectorProvider provider,
                                 AfUnix.Address parentLocalAddress,
                                 AfUnix.Address remoteAddress,
                                 int childSocket) {
        super(provider, parentLocalAddress, remoteAddress, childSocket);
    }

}
