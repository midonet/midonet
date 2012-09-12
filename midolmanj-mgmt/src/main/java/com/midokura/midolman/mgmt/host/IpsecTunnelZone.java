/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import java.util.UUID;

/**
 * Class representing IPSEC Tunnel zone.
 */
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
            com.midokura.midonet.cluster.data.zones.IpsecTunnelZone
                    tunnelZoneData) {
        super(tunnelZoneData);
    }

    @Override
    public String getType() {
        return TunnelZoneType.IPSEC;
    }

    @Override
    public com.midokura.midonet.cluster.data.TunnelZone toData() {
        com.midokura.midonet.cluster.data.zones.IpsecTunnelZone zone =
                new com.midokura.midonet.cluster.data.zones.IpsecTunnelZone();
        super.setConfig(zone);
        return zone;
    }
}
