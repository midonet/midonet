/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.cluster.data.TunnelZone;

public interface TunnelZones {
    interface BuildersProvider {
        Builder getZoneBuilder();
    }

    interface Builder extends org.midonet.cluster.client.Builder<Builder> {
        public interface ZoneConfig {
            TunnelZone getTunnelZoneConfig();
        }

        Builder setConfiguration(TunnelZone configuration);

        Builder addHost(UUID hostId, TunnelZone.HostConfig hostConfig);

        Builder removeHost(UUID hostId, TunnelZone.HostConfig hostConfig);
    }
}
