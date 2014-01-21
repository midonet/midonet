/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkMessage.AttrKey;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;

public class VxLanTunnelPortOptions implements BuilderAware {

    public static final NetlinkMessage.AttrKey<Short> OVS_TUNNEL_ATTR_DST_PORT =
        AttrKey.attr(OpenVSwitch.Port.VPortTunnelOptions.DstPort);

    public static short VXLAN_DEFAULT_DST_PORT = 4789;

    private short dstPort = VXLAN_DEFAULT_DST_PORT;

    public VxLanTunnelPortOptions() { }

    public VxLanTunnelPortOptions(int dstPort) {
        setDestinationPort(dstPort);
    }

    public short getDestinationPort() {
        return this.dstPort;
    }

    private void setDestinationPort(int dstPort) {
        TCP.ensurePortInRange(dstPort);
        this.dstPort = (short) dstPort;
    }

    @Override
    public void serialize(Builder builder) {
        // The datapath code checks for a u16 attribute written without padding,
        // therefore the len field of the header should indicate 6b.
        builder.addAttrNoPad(OVS_TUNNEL_ATTR_DST_PORT, dstPort);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            int port = message.getAttrValueShort(OVS_TUNNEL_ATTR_DST_PORT);
            setDestinationPort(port);
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
