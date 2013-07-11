/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;

/**
 * Abstracts a netlink channel. The implementation will make a native netlink
 * socket connection to the local machine.
 */
public abstract class NetlinkChannel extends UnixChannel<Netlink.Address> {

    protected Netlink.Protocol protocol;

    protected NetlinkChannel(SelectorProvider provider, Netlink.Protocol protocol) {
        super(provider);
        this.protocol = protocol;
    }

    public boolean connect(Netlink.Address address) throws IOException {
        return _connect(address);
    }

    public Netlink.Address getRemoteAddress() {
        return remoteAddress;
    }

    @Nullable
    public Netlink.Address getLocalAddress() {
        return localAddress;
    }

}
