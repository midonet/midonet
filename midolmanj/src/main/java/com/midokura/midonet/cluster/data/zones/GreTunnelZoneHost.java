/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.midonet.cluster.data.TunnelZone.HostConfig;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class GreTunnelZoneHost
        extends HostConfig<GreTunnelZoneHost, GreTunnelZoneHost.Data> {

    public GreTunnelZoneHost() {
        this(null, new Data());
    }

    public GreTunnelZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public GreTunnelZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected GreTunnelZoneHost self() {
        return this;
    }

    public static class Data extends HostConfig.Data {
        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            return true;
        }
    }
}
