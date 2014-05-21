/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.TunnelZone;

public class GreTunnelZone
    extends TunnelZone<GreTunnelZone, GreTunnelZone.Data> {

    public GreTunnelZone() {
        super(null, new Data());
    }

    public GreTunnelZone(UUID uuid) {
        super(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Gre;
    }

    public GreTunnelZone(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected GreTunnelZone self() {
        return this;
    }

    public static class Data extends TunnelZone.Data {

    }
}
