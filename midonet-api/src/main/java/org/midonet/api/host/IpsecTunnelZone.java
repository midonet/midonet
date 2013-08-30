/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.util.UUID;

import org.midonet.api.host.validation.IsUniqueIpsecTunnelZoneName;

/**
 * Class representing IPSEC Tunnel zone.
 */
@IsUniqueIpsecTunnelZoneName
public class IpsecTunnelZone extends TunnelZone {

    /**
     * Constructor.
     */
    public IpsecTunnelZone() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the tunnel zone.
     * @param name
     *            Name of the tunnel zone.
     */
    public IpsecTunnelZone(UUID id, String name) {
        super(id, name);
    }

    /**
     * Tunnel zone constructor
     *
     * @param tunnelZoneData
     *            TunnelZone data object
     */
    public IpsecTunnelZone(
            org.midonet.cluster.data.zones.IpsecTunnelZone
                    tunnelZoneData) {
        super(tunnelZoneData);
    }

    @Override
    public String getType() {
        return TunnelZoneType.IPSEC;
    }

    @Override
    public org.midonet.cluster.data.TunnelZone toData() {
        org.midonet.cluster.data.zones.IpsecTunnelZone zone =
                new org.midonet.cluster.data.zones.IpsecTunnelZone();
        super.setConfig(zone);
        return zone;
    }
}
