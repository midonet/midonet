/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.util.netlink.dp.Port;

/**
 * Description to an internal datapath port.
 */
public class InternalPort extends Port<InternalPort.Options, InternalPort> {

    public InternalPort(@Nonnull String name) {
        super(name, Type.Internal);
    }

    @Override
    protected InternalPort self() {
        return this;
    }

    public class Options extends AbstractPortOptions {

    }
}
