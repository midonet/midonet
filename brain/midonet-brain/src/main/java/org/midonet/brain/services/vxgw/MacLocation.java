package org.midonet.brain.services.vxgw;
/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

import java.util.Objects;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * Represents the association of a MAC in the given logical switch to one
 * VxLAN Tunnel Endpoint with the given IP.
 */
public final class MacLocation {

    public final String logicalSwitchName;
    public final MAC mac;
    public final IPv4Addr vxlanTunnelEndpoint;

    public MacLocation(final MAC mac,
                       final String logicalSwitchName,
                       final IPv4Addr vxlanTunnelEndpoint) {
        if (mac == null)
            throw new IllegalArgumentException("MAC cannot be null");
        if (logicalSwitchName == null)
            throw new IllegalArgumentException("Log. Switch name can't be null");
        this.mac = mac;
        this.logicalSwitchName = logicalSwitchName;
        this.vxlanTunnelEndpoint = vxlanTunnelEndpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MacLocation that = (MacLocation) o;

        return Objects.equals(mac, that.mac) &&
               Objects.equals(logicalSwitchName, that.logicalSwitchName) &&
               Objects.equals(vxlanTunnelEndpoint, that.vxlanTunnelEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mac, logicalSwitchName, vxlanTunnelEndpoint);
    }

    @Override
    public String toString() {
        return "MacLocation{" +
            "logicalSwitchName='" + logicalSwitchName + '\'' +
            ", mac=" + mac +
            ", vxlanTunnelEndpoint=" + vxlanTunnelEndpoint +
            '}';
    }
}
