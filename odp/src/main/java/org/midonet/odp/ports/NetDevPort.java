/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import org.midonet.odp.DpPort;

/**
 * Description of a port that maps to a local netdev device.
 */
public class NetDevPort extends DpPort {

    public NetDevPort(String name) {
        super(name);
    }

    public Type getType() {
        return Type.NetDev;
    }

}
