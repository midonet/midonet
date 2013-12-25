/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.odp.Port;

/**
 * Description to an internal datapath port.
 */
public class InternalPort extends Port<InternalPortOptions, InternalPort> {

    public InternalPort(@Nonnull String name) {
        super(name, Type.Internal);
    }

}
