/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.packets.IntIPv4;
import com.midokura.midonet.cluster.data.TunnelZone.HostConfig;

/**
 * Holder for a Capwap Tunnel per host config.
 */
public class CapwapTunnelZoneHost
        extends HostConfig<CapwapTunnelZoneHost, CapwapTunnelZoneHost.Data> {

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

    @Override
    public CapwapTunnelZoneHost setIp(IntIPv4 ip) {
        return super.setIp(ip);
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
