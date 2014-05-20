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

    public final String logicalSwitchId;
    public final MAC mac;
    public final IPv4Addr vxlanTunnelEndpoint;

    public MacLocation(final MAC mac,
                       final String logicalSwitchId,
                       final IPv4Addr vxlanTunnelEndpoint) {
        if (mac == null)
            throw new IllegalArgumentException("MAC cannot be null");
        if (logicalSwitchId == null)
            throw new IllegalArgumentException("Log. Switch ID cannot be null");
        this.mac = mac;
        this.logicalSwitchId = logicalSwitchId;
        this.vxlanTunnelEndpoint = vxlanTunnelEndpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MacLocation that = (MacLocation) o;

        return Objects.equals(mac, that.mac) &&
               Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
               Objects.equals(vxlanTunnelEndpoint, that.vxlanTunnelEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mac, logicalSwitchId, vxlanTunnelEndpoint);
    }

    @Override
    public String toString() {
        return "MacLocation{" +
            "logicalSwitchId='" + logicalSwitchId + '\'' +
            ", mac=" + mac +
            ", vxlanTunnelEndpoint=" + vxlanTunnelEndpoint +
            '}';
    }
}
