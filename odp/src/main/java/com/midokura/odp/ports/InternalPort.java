/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.ports;

import javax.annotation.Nonnull;

import com.midokura.odp.Port;

/**
 * Description to an internal datapath port.
 */
public class InternalPort extends Port<InternalPortOptions, InternalPort> {

    public InternalPort(@Nonnull String name) {
        super(name, Type.Internal);
    }

    @Override
    public InternalPortOptions newOptions() {
        return new InternalPortOptions();
    }

    @Override
    protected InternalPort self() {
        return this;
    }

}
