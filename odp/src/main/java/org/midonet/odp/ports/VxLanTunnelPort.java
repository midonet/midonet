/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.midonet.odp.DpPort;
import org.midonet.odp.family.PortFamily;

/**
 * Description of a VxLAN tunnel datapath port.
 */
public class VxLanTunnelPort extends DpPort {

    VxLanTunnelPortOptions options;

    public VxLanTunnelPort(@Nonnull String name) {
        super(name, Type.VXLan);
    }

    public VxLanTunnelPort(@Nonnull String name, VxLanTunnelPortOptions opts) {
        super(name, Type.VXLan);
        this.options = opts;
    }

    @Override
    public void serializeInto(Builder builder) {
        super.serializeInto(builder);
        builder.addAttr(PortFamily.Attr.VXLANOPTIONS, options);
    }

    @Override
    protected void deserializeFrom(NetlinkMessage msg) {
        super.deserializeFrom(msg);
        this.options = msg.getAttrValue(PortFamily.Attr.VXLANOPTIONS,
                                        new VxLanTunnelPortOptions());
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o))
            return false;

        @SuppressWarnings("unchecked") // safe cast
        VxLanTunnelPort that = (VxLanTunnelPort) o;

        return options == null ?
                  that.options == null : options.equals(that.options);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (options != null ? options.hashCode() : 0);
        return result;
    }


    @Override
    public String toString() {
        return "DpPort{" +
            "portNo=" + portNo +
            ", type=" + type +
            ", name='" + name + '\'' +
            ", options=" + options +
            ", stats=" + stats +
            '}';
    }

    /** returns a new VxLanTunnelPort instance with default options */
    public static VxLanTunnelPort make(String name) {
        return new VxLanTunnelPort(name, new VxLanTunnelPortOptions());
    }

}
