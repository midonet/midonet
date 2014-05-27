/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.TunnelZone;

public class CapwapTunnelZone
    extends TunnelZone<CapwapTunnelZone, CapwapTunnelZone.Data> {

    public CapwapTunnelZone() {
        this(null, new Data());
    }

    public CapwapTunnelZone(UUID uuid) {
        this(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Capwap;
    }

    public CapwapTunnelZone(UUID zoneId, @Nonnull Data data) {
        super(zoneId, data);
    }

    @Override
    protected CapwapTunnelZone self() {
        return this;
    }

    public static class Data extends TunnelZone.Data {

    }
}
