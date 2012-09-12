/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.midonet.cluster.data.TunnelZone;

import java.util.UUID;

/**
 * Capwap tunnel zone host mapping
 */
public class CapwapTunnelZoneHost extends TunnelZoneHost {

    public CapwapTunnelZoneHost() {
        super();
    }

    public CapwapTunnelZoneHost(UUID tunnelZoneId,
            com.midokura.midonet.cluster.data.zones.CapwapTunnelZoneHost
                    data) {
        super(tunnelZoneId, data);
    }

    @Override
    public TunnelZone.HostConfig toData() {
        com.midokura.midonet.cluster.data.zones.CapwapTunnelZoneHost data
                = new com.midokura.midonet.cluster.data.zones
                .CapwapTunnelZoneHost();
        setData(data);
        return data;
    }

}
