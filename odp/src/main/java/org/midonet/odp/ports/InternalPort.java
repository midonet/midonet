/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.odp.DpPort;

/**
 * Description to an internal datapath port.
 */
public class InternalPort extends DpPort {

    public InternalPort(@Nonnull String name) {
        super(name, Type.Internal);
    }

}
