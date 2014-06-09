/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.ports;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;

public class VxLanTunnelPortOptions implements BuilderAware {

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
    public boolean deserialize(NetlinkMessage message) {
        try {
            short portId = OpenVSwitch.Port.VPortTunnelOptions.DstPort;
            int port = message.getAttrValueShort(portId);
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
