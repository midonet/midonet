/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.TunnelZone;

/**
 *
 */
public class GreTunnelZone
    extends TunnelZone<GreTunnelZone, GreTunnelZone.Data> {

    public static final short TUNNEL_OVERHEAD = (short)46;

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

    @Override
    /*
     * from OVS datapath/vport-gre.c:
     * ethernet header length + IP header length + 
     * sizeof(struct gre_base_hdr) + [if checksum] + [if out_key]
     * 14 + 20 + 4 + 4 + 4 = 46
     */
    public short getTunnelOverhead() {
        return TUNNEL_OVERHEAD;
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
