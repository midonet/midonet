/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public class VxLanTunnelPortOptions implements BuilderAware {
    public static short VXLAN_DEFAULT_DST_PORT = 4789;

    short dstPort = VXLAN_DEFAULT_DST_PORT;

    public VxLanTunnelPortOptions setDestinationPort(short dstPort) {
        this.dstPort = dstPort;
        return this;
    }

    public short getDestinationPort() {
        return this.dstPort;
    }

    static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        public static final Attr<Short> OVS_TUNNEL_ATTR_DST_PORT =
            attr(OpenVSwitch.Port.VPortTunnelOptions.DstPort);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addAttr(Attr.OVS_TUNNEL_ATTR_DST_PORT, dstPort, ByteOrder.BIG_ENDIAN);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            dstPort = message.getAttrValueShort(Attr.OVS_TUNNEL_ATTR_DST_PORT,
                    ByteOrder.BIG_ENDIAN);
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        if (!super.equals(o))
            return false;

        VxLanTunnelPortOptions that = (VxLanTunnelPortOptions) o;
        return (dstPort == that.dstPort);
    }

    @Override
    public int hashCode() {
        return dstPort;
    }

    @Override
    public String toString() {
        return "TunnelPortOptions{" +
                ", dstPort=" + dstPort +
                '}';
    }
}
