/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.host;

import com.midokura.midonet.cluster.data.TunnelZone;

import java.util.UUID;

/**
 * GRE tunnel zone host mapping
 */
public class GreTunnelZoneHost extends TunnelZoneHost {

    public GreTunnelZoneHost() {
        super();
    }

    public GreTunnelZoneHost(UUID tunnelZoneId,
            com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost data) {
        super(tunnelZoneId, data);
    }

    @Override
    public TunnelZone.HostConfig toData() {
        com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost data =
                new com.midokura.midonet.cluster.data.zones
                        .GreTunnelZoneHost();
        setData(data);
        return data;
    }
}
