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
public class CapwapTunnelZone
    extends TunnelZone<CapwapTunnelZone, CapwapTunnelZone.Data> {

    public static final short TUNNEL_OVERHEAD = (short)62;

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

    @Override
    public short getTunnelOverhead() {
        /*
         * from OVS datapath/vport-capwap.c, overhead should be
         * ethernet hdr size + IPv4 hdr size + UDP hdr size +
         * sizeof(struct capwaphdr) + sizeof(struct capwaphdr_wsi) +
         * sizeof(struct capwaphdr_wsi_key) ===
         * 14 + 20 + 8 + 8 + 4 + 8 = 62
         */
        return TUNNEL_OVERHEAD;
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
