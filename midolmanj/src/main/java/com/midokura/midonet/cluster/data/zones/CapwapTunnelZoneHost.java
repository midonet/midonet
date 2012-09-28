/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.TunnelZone.HostConfig;

/**
 * Holder for a Capwap Tunnel per host config.
 */
public class CapwapTunnelZoneHost
        extends HostConfig<CapwapTunnelZoneHost, HostConfig.Data> {

    public CapwapTunnelZoneHost() {
        this(null, new HostConfig.Data());
    }

    public CapwapTunnelZoneHost(UUID uuid) {
        this(uuid, new HostConfig.Data());
    }

    public CapwapTunnelZoneHost(UUID uuid, @Nonnull HostConfig.Data data) {
        super(uuid, data);
    }

    @Override
    protected CapwapTunnelZoneHost self() {
        return this;
    }
}
