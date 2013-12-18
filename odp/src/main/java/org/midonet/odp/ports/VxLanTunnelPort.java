/*
* Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.odp.Port;

/**
 * Description of a VxLAN tunnel datapath port.
 */
public class VxLanTunnelPort extends Port<VxLanTunnelPortOptions, VxLanTunnelPort> {

    public VxLanTunnelPort(@Nonnull String name) {
        super(name, Type.VXLan);
    }

    @Override
    protected VxLanTunnelPort self() {
        return this;
    }

    @Override
    public VxLanTunnelPortOptions newOptions() {
        return new VxLanTunnelPortOptions();
    }

    /** returns a new VxLanTunnelPort instance with default options */
    public static VxLanTunnelPort make(String name) {
        VxLanTunnelPort p = new VxLanTunnelPort(name);
        p.setOptions(new VxLanTunnelPortOptions());
        return p;
    }

}
