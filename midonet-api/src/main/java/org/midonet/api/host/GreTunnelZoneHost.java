/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.cluster.data.TunnelZone;

import java.util.UUID;

/**
 * GRE tunnel zone host mapping
 */
public class GreTunnelZoneHost extends TunnelZoneHost {

    public GreTunnelZoneHost() {
        super();
    }

    public GreTunnelZoneHost(UUID tunnelZoneId,
            org.midonet.cluster.data.zones.GreTunnelZoneHost data) {
        super(tunnelZoneId, data);
    }

    @Override
    public org.midonet.cluster.data.zones.GreTunnelZoneHost toData() {
        org.midonet.cluster.data.zones.GreTunnelZoneHost data =
                new org.midonet.cluster.data.zones
                        .GreTunnelZoneHost();
        setData(data);
        return data;
    }
}
