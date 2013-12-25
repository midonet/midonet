/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.odp.Port;

/**
 * Description of a port that maps to a local netdev device.
 */
public class NetDevPort extends Port<NetDevPortOptions, NetDevPort> {

    public NetDevPort(@Nonnull String name) {
        super(name, Type.NetDev);
    }

}
