/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.host;

import java.util.UUID;

/**
 * Class representing Capwap Tunnel zone.
 */
public class CapwapTunnelZone extends TunnelZone {

    /**
     * Constructor.
     */
    public CapwapTunnelZone() {
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
    public CapwapTunnelZone(UUID id, String name) {
        super(id, name);
    }

    /**
     * Tunnel zone constructor
     *
     * @param tunnelZoneData
     *            TunnelZone data object
     */
    public CapwapTunnelZone(
            com.midokura.midonet.cluster.data.zones.CapwapTunnelZone
                    tunnelZoneData) {
        super(tunnelZoneData);
    }

    @Override
    public String getType() {
        return TunnelZoneType.CAPWAP;
    }

    @Override
    public com.midokura.midonet.cluster.data.TunnelZone toData() {
        com.midokura.midonet.cluster.data.zones.CapwapTunnelZone zone =
                new com.midokura.midonet.cluster.data.zones.CapwapTunnelZone();
        super.setConfig(zone);
        return zone;
    }
}
