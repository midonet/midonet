/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.TunnelZone;

/**
 * Holder for a Capwap Tunnel per host config.
 */
public class CapwapTunnelZoneHost
    extends
    TunnelZone.HostConfig<CapwapTunnelZoneHost, CapwapTunnelZoneHost.Data> {

    public CapwapTunnelZoneHost() {
        this(null, new Data());
    }

    public CapwapTunnelZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public CapwapTunnelZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected CapwapTunnelZoneHost self() {
        return this;
    }

    public static class Data extends TunnelZone.HostConfig.Data {
    }
}
