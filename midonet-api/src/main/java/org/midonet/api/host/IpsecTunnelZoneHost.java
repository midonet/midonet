/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.util.UUID;

/**
 * IPSEC tunnel zone host mapping
 */
public class IpsecTunnelZoneHost extends TunnelZoneHost {

    public IpsecTunnelZoneHost() {
        super();
    }

    public IpsecTunnelZoneHost(UUID tunnelZoneId,
            org.midonet.cluster.data.zones.IpsecTunnelZoneHost data) {
        super(tunnelZoneId, data);
    }

    @Override
    public org.midonet.cluster.data.zones.IpsecTunnelZoneHost toData() {
        org.midonet.cluster.data.zones.IpsecTunnelZoneHost data
                = new org.midonet.cluster.data.zones
                .IpsecTunnelZoneHost();
        setData(data);
        return data;
    }

}
