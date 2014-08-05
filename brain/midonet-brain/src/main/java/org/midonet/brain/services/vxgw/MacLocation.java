/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Objects;

import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.packets.IPv4Addr;

/**
 * Represents the association of a MAC in the given logical switch to one
 * VxLAN Tunnel Endpoint with the given IP.
 */
public class MacLocation {

    public final String logicalSwitchName;
    public final VtepMAC mac;
    public final IPv4Addr vxlanTunnelEndpoint;

    /**
     * @param mac the MAC address whose location is represented here
     * @param logicalSwitchName the logical switch referenced by this location
     * @param vxlanTunnelEndpoint the address of the endpoint that has this
     *                            MAC for the given logical switch
     */
    public MacLocation(VtepMAC mac, String logicalSwitchName,
                       IPv4Addr vxlanTunnelEndpoint) {
        assert(mac != null);
        assert(logicalSwitchName != null);
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
