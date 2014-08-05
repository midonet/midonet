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
    public final IPv4Addr ipAddr;
    public final IPv4Addr vxlanTunnelEndpoint;

    /**
     * MacLocation constructor
     * @param mac is the MAC address to which the rest of information relates.
     * @param ipAddr is an IP that has been associated to the MAC (either
     *               learned or explicitly set. Can be null.
     * @param logicalSwitchName  is the name of the logical switch (usually the
     *                           midonet bridge uuid prefixed by "mn-".
     * @param vxlanTunnelEndpoint  is the tunnel ip for the host/vtep containing
     *                             the port associated to the MAC. It can be
     *                             null, indicating that the MAC is no longer
     *                             associated to a particular port or a
     *                             particular IP.
     */
    public MacLocation(final VtepMAC mac,
                       final IPv4Addr ipAddr,
                       final String logicalSwitchName,
                       final IPv4Addr vxlanTunnelEndpoint) {
        if (mac == null)
            throw new IllegalArgumentException("MAC cannot be null");
        if (logicalSwitchName == null)
            throw new IllegalArgumentException("L. Switch name can't be null");
        this.mac = mac;
        this.ipAddr = ipAddr;
        this.logicalSwitchName = logicalSwitchName;
        this.vxlanTunnelEndpoint = vxlanTunnelEndpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MacLocation that = (MacLocation) o;

        return Objects.equals(mac, that.mac) &&
               Objects.equals(ipAddr, that.ipAddr) &&
               Objects.equals(logicalSwitchName, that.logicalSwitchName) &&
               Objects.equals(vxlanTunnelEndpoint, that.vxlanTunnelEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mac, ipAddr, logicalSwitchName,
                            vxlanTunnelEndpoint);
    }

    @Override
    public String toString() {
        return "MacLocation{" +
            "logicalSwitchName='" + logicalSwitchName + '\'' +
            ", mac=" + mac +
            ", ipAddr=" + ipAddr +
            ", vxlanTunnelEndpoint=" + vxlanTunnelEndpoint +
            '}';
    }
}
