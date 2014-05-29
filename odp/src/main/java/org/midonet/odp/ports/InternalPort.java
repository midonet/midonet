/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import org.midonet.odp.DpPort;

/**
 * Description to an internal datapath port.
 */
public class InternalPort extends DpPort {

    public InternalPort(String name) {
        super(name);
    }

    public Type getType() {
        return Type.Internal;
    }

}
