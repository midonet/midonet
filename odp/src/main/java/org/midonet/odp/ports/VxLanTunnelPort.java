/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.DpPort;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.family.PortFamily;

/**
 * Description of a VxLAN tunnel datapath port.
 */
public class VxLanTunnelPort extends DpPort {

    private VxLanTunnelPortOptions options;

    public VxLanTunnelPort(String name) {
        super(name);
    }

    public VxLanTunnelPort(String name, VxLanTunnelPortOptions opts) {
        super(name);
        this.options = opts;
    }

    public Type getType() {
        return Type.VXLan;
    }

    /** Serializes this VxLanTunnelPort object into a ByteBuffer. If the
     *  'options' instance variable is null and not serialized into the
     *  ByteBuffer, the datapath will return EINVAL to port_create requests. */
    @Override
    public void serializeInto(ByteBuffer buf) {
        super.serializeInto(buf);
        if (options != null) {
            // OVS_VPORT_ATTR_OPTIONS is a nested netlink attribute. Here we
            // write its netlink header manually. The len field of the header is
            // equal to 2b + 2b for the header part + 8b for the attribute part.
            // The attribute is a single port field (u16) which we write with
            // padding (4b), and whose own header takes 4b. Note that the header
            // of the port attribute has its len field written without padding
            buf.putShort((short)12);
            buf.putShort((short)OpenVSwitch.Port.Attr.Options);
            // The datapath code checks for a u16 attribute written without
            // padding, therefore the len field of the header should be 6b.
            short portAttrId = (short)OpenVSwitch.Port.VPortTunnelOptions.DstPort;
            short dstPort = options.getDestinationPort();
            NetlinkMessage.writeShortAttrNoPad(buf, portAttrId, dstPort);
        }
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
            "portNo=" + getPortNo() +
            ", type=" + getType() +
            ", name='" + getName() + '\'' +
            ", options=" + options +
            ", stats=" + getStats() +
            '}';
    }

    /** returns a new VxLanTunnelPort instance with default udp port value */
    public static VxLanTunnelPort make(String name) {
        return new VxLanTunnelPort(name, new VxLanTunnelPortOptions());
    }

    /** returns a new VxLanTunnelPort instance with given udp port value */
    public static VxLanTunnelPort make(String name, int port) {
        return new VxLanTunnelPort(name, new VxLanTunnelPortOptions(port));
    }
}
