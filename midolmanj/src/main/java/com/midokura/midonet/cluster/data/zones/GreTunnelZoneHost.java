/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.packets.IntIPv4;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class GreTunnelZoneHost
    extends
    TunnelZone.HostConfig<GreTunnelZoneHost, GreTunnelZoneHost.Data> {

    public GreTunnelZoneHost() {
        this(null, new Data());
    }

    public GreTunnelZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public GreTunnelZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected GreTunnelZoneHost self() {
        return this;
    }

    public GreTunnelZoneHost setIp(IntIPv4 ip) {
        getData().ip = ip;
        return this;
    }

    public IntIPv4 getIp() {
        return getData().ip;
    }

    public static class Data extends TunnelZone.HostConfig.Data {
        IntIPv4 ip;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (ip != null ? !ip.equals(data.ip) : data.ip != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return ip != null ? ip.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Data{" +
                "ip=" + ip +
                '}';
        }
    }
}
