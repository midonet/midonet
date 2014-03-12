/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.cluster.data.TunnelZone;

import java.util.UUID;

public class TunnelZoneHostFactory {

    public static TunnelZoneHost createTunnelZoneHost(UUID tunnelZoneId,
            TunnelZone.HostConfig<?, ?> data) {

        if (data instanceof
                org.midonet.cluster.data.zones.CapwapTunnelZoneHost) {
            return new CapwapTunnelZoneHost(tunnelZoneId,
                    (org.midonet.cluster.data.zones
                            .CapwapTunnelZoneHost) data);
        } else if (data instanceof
                org.midonet.cluster.data.zones.GreTunnelZoneHost) {
            return new GreTunnelZoneHost(tunnelZoneId,
                    (org.midonet.cluster.data.zones.GreTunnelZoneHost)
                            data);
        } else if (data instanceof
                org.midonet.cluster.data.zones.IpsecTunnelZoneHost) {
            return new IpsecTunnelZoneHost(tunnelZoneId,
                    (org.midonet.cluster.data.zones
                            .IpsecTunnelZoneHost) data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this tunnel zone host type.");
        }
    }
}
