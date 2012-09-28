/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.TunnelZone.HostConfig;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class GreTunnelZoneHost
        extends HostConfig<GreTunnelZoneHost, HostConfig.Data> {

    public GreTunnelZoneHost() {
        this(null, new HostConfig.Data());
    }

    public GreTunnelZoneHost(UUID uuid) {
        this(uuid, new HostConfig.Data());
    }

    public GreTunnelZoneHost(UUID uuid, @Nonnull HostConfig.Data data) {
        super(uuid, data);
    }

    @Override
    protected GreTunnelZoneHost self() {
        return this;
    }
}
