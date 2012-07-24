/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.sdn.dp.Port;

/**
 * Description of a port that maps to a local netdev device.
 */
public class NetDevPort extends Port<NetDevPort.Options, NetDevPort> {

    public NetDevPort(@Nonnull String name) {
        super(name, Type.NetDev);
    }

    @Override
    public Options newOptions() {
        return new Options();
    }

    @Override
    protected NetDevPort self() {
        return this;
    }

    public class Options extends AbstractPortOptions {
    }
}
