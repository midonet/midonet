/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import java.util.UUID;

/**
 * IPSEC tunnel zone host mapping
 */
public class IpsecTunnelZoneHost extends TunnelZoneHost {

    public IpsecTunnelZoneHost() {
        super();
    }

    public IpsecTunnelZoneHost(UUID tunnelZoneId,
            com.midokura.midonet.cluster.data.zones.IpsecTunnelZoneHost data) {
        super(tunnelZoneId, data);
    }

    @Override
    public com.midokura.midonet.cluster.data.TunnelZone.HostConfig toData() {
        com.midokura.midonet.cluster.data.zones.IpsecTunnelZoneHost data
                = new com.midokura.midonet.cluster.data.zones
                .IpsecTunnelZoneHost();
        setData(data);
        return data;
    }

}
