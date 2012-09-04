/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 *
 */
public class Host extends Entity.Base<UUID, Host.Data, Host>{

    public Host() {
        super(null, new Data());
    }

    public Host(UUID uuid) {
        super(uuid, new Data());
    }

    @Override
    protected Host self() {
        return this;
    }

    public Host setName(String name) {
        getData().name = name;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public Host setAddresses(InetAddress[] addresses) {
        getData().addresses = addresses;
        return self();
    }

    public InetAddress[] getAddresses() {
        return getData().addresses;
    }

    public Host setTunnelZones(Set<UUID> tunnelZones) {
        getData().tunnelZones = tunnelZones;
        return self();
    }

    public Set<UUID> getAvailabilityZones() {
        return getData().tunnelZones;
    }

    public static class Data {
        String name;
        InetAddress[] addresses;
        Set<UUID> tunnelZones = new HashSet<UUID>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (!Arrays.equals(addresses, data.addresses)) return false;
            if (tunnelZones != null ? !tunnelZones.equals(
                data.tunnelZones) : data.tunnelZones != null)
                return false;
            if (name != null ? !name.equals(data.name) : data.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + (addresses != null ? Arrays.hashCode(
                addresses) : 0);
            result = 31 * result + (tunnelZones != null ? tunnelZones
                .hashCode() : 0);
            return result;
        }
    }
}
