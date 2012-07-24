/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.util.netlink.dp.Port;

/**
 * Description of a GRE tunnel datapath port.
 */
public class GreTunnelPort extends Port<GreTunnelPort.Options, GreTunnelPort> {

    public GreTunnelPort(@Nonnull String name) {
        super(name, Type.Gre);
    }

    @Override
    protected GreTunnelPort self() {
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
