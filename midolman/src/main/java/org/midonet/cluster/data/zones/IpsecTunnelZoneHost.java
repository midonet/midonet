/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import org.midonet.cluster.data.TunnelZone;

/**
 * Per host configuration for a ip sec tunnel zone.
 */
public class IpsecTunnelZoneHost
    extends TunnelZone.HostConfig<IpsecTunnelZoneHost, IpsecTunnelZoneHost.Data> {

    public IpsecTunnelZoneHost() {
        this(null, new Data());
    }

    public IpsecTunnelZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public IpsecTunnelZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected IpsecTunnelZoneHost self() {
        return this;
    }

    public static class Data extends TunnelZone.HostConfig.Data {
    }
}
