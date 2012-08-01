/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.sdn.dp.Port;

/**
 * A CapWap Tunnel datapath port.
 */
public class CapWapTunnelPort extends Port<CapwapTunnelPortOptions, CapWapTunnelPort> {

    public CapWapTunnelPort(@Nonnull String name) {
        super(name, Type.CapWap);
    }

    @Override
    protected CapWapTunnelPort self() {
        return this;
    }

    @Override
    public CapwapTunnelPortOptions newOptions() {
        return new CapwapTunnelPortOptions();
    }

}
