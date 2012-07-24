/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.util.netlink.dp.Port;

/**
 * A CapWap Tunnel datapath port.
 */
public class CapWapTunnelPort extends Port<CapWapTunnelPort.Options, CapWapTunnelPort> {

    public CapWapTunnelPort(@Nonnull String name) {
        super(name, Type.CapWap);
    }

    @Override
    protected CapWapTunnelPort self() {
        return this;
    }

    @Override
    public Options newOptions() {
        return new Options();
    }

    public class Options extends TunnelPortOptions<Options> {
        @Override
        protected Options self() {
            return this;
        }
    }
}
