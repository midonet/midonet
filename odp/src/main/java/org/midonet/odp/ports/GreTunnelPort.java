/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import org.midonet.odp.DpPort;

/**
 * Description of a GRE tunnel datapath port.
 */
public class GreTunnelPort extends DpPort {

    public GreTunnelPort(String name) {
        super(name);
    }

    public Type getType() {
        return Type.Gre;
    }

    /** returns a new GreTunnelPort instance with empty options */
    public static GreTunnelPort make(String name) {
        return new GreTunnelPort(name);
    }

}
