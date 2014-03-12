/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.util.UUID;

/**
 * Class representing GRE Tunnel zone.
 */
public class GreTunnelZone extends TunnelZone {

    /**
     * Constructor.
     */
    public GreTunnelZone() {
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
    public GreTunnelZone(UUID id, String name) {
        super(id, name);
    }

    /**
     * Tunnel zone constructor
     *
     * @param tunnelZoneData
     *            TunnelZone data object
     */
    public GreTunnelZone(
            org.midonet.cluster.data.zones.GreTunnelZone
                    tunnelZoneData) {
        super(tunnelZoneData);
    }

    @Override
    public String getType() {
        return TunnelZoneType.GRE;
    }

    @Override
    public org.midonet.cluster.data.zones.GreTunnelZone toData() {
        org.midonet.cluster.data.zones.GreTunnelZone zone =
                new org.midonet.cluster.data.zones.GreTunnelZone();
        super.setConfig(zone);
        return zone;
    }
}
