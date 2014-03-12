/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.cluster.data.TunnelZone;

import java.util.UUID;

/**
 * Capwap tunnel zone host mapping
 */
public class CapwapTunnelZoneHost extends TunnelZoneHost {

    public CapwapTunnelZoneHost() {
        super();
    }

    public CapwapTunnelZoneHost(UUID tunnelZoneId,
            org.midonet.cluster.data.zones.CapwapTunnelZoneHost
                    data) {
        super(tunnelZoneId, data);
    }

    @Override
    public org.midonet.cluster.data.zones.CapwapTunnelZoneHost toData() {
        org.midonet.cluster.data.zones.CapwapTunnelZoneHost data
                = new org.midonet.cluster.data.zones
                .CapwapTunnelZoneHost();
        setData(data);
        return data;
    }

}
