/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.cluster.data.TunnelZone;

import java.util.UUID;

public class TunnelZoneHostFactory {

    public static TunnelZoneHost createTunnelZoneHost(UUID tunnelZoneId,
            TunnelZone.HostConfig data) {
        return new TunnelZoneHost(tunnelZoneId, data);
    }
}
