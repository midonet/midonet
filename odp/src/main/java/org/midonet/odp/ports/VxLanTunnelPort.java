/*
* Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.odp.Port;
import org.midonet.odp.PortOptions;
import org.midonet.odp.family.PortFamily;

/**
 * Description of a VxLAN tunnel datapath port.
 */
public class VxLanTunnelPort extends Port<VxLanTunnelPortOptions, VxLanTunnelPort> {

    public VxLanTunnelPort(@Nonnull String name) {
        super(name, Type.VXLan);
    }

    public VxLanTunnelPort(@Nonnull String name, VxLanTunnelPortOptions opts) {
        super(name, Type.VXLan);
        this.options = opts;
    }

    @Override
    protected VxLanTunnelPort self() {
        return this;
    }

    @Override
    public void serializeInto(Builder builder) {
        super.serializeInto(builder);
        builder.addAttr(PortFamily.Attr.OPTIONS, options);
    }

    @Override
    public boolean supportOptions() {
        return true;
    }

    @Override
    public VxLanTunnelPort setOptionsFrom(NetlinkMessage msg) {
        VxLanTunnelPortOptions newOpts = new VxLanTunnelPortOptions();
        this.options =
            (VxLanTunnelPortOptions)
                msg.getAttrValue(PortFamily.Attr.OPTIONS, newOpts);
        return self();
    }

    /** returns a new VxLanTunnelPort instance with default options */
    public static VxLanTunnelPort make(String name) {
        return new VxLanTunnelPort(name, new VxLanTunnelPortOptions());
    }

}
