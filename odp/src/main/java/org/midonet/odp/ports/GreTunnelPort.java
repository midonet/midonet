/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.odp.Port;

/**
 * Description of a GRE tunnel datapath port.
 */
public class GreTunnelPort extends Port<GreTunnelPortOptions, GreTunnelPort> {

    public GreTunnelPort(@Nonnull String name) {
        super(name, Type.Gre);
    }

    @Override
    protected GreTunnelPort self() {
        return this;
    }

    /** returns a new GreTunnelPort instance with empty options */
    public static GreTunnelPort make(String name) {
        return new GreTunnelPort(name);
    }

}
