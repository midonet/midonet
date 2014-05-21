/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.TunnelZone;

public class IpsecTunnelZone
    extends TunnelZone<IpsecTunnelZone, IpsecTunnelZone.Data> {

    public IpsecTunnelZone() {
        this(null, new Data());
    }

    public IpsecTunnelZone(UUID uuid) {
        this(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Ipsec;
    }

    public IpsecTunnelZone(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected IpsecTunnelZone self() {
        return this;
    }

    public static class Data extends TunnelZone.Data {

    }
}
