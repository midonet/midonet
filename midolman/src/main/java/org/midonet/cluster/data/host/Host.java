/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.host;

import org.midonet.cluster.data.Entity;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Host metadata
 */
public class Host extends Entity.Base<UUID, Host.Data, Host> {

    static public final int IGNORE_FLOODING_PROXY_WEIGHT = 0;
    static public final int DEFAULT_FLOODING_PROXY_WEIGHT = 1;

    private boolean isAlive;

    /*
     * Flooding-Proxy weight.
     * This value defines the preference for this host to be chosen as a
     * VTEP's flooding proxy. The higher the value, the higher the probability.
     * A zero value prevents this host from being chosen.
     * If the value is not explicitly set, then the default value should be 1.
     */
    private int floodingProxyWeight;

    public Host() {
        this(null, new Data());
    }

    public Host(UUID uuid) {
        this(uuid, new Data());
    }

    public Host(UUID uuid, Data data) {
        super(uuid, data);
        floodingProxyWeight = DEFAULT_FLOODING_PROXY_WEIGHT;
    }

    @Override
    protected Host self() {
        return this;
    }
    
    public String getName() {
        return getData().name;
    }

    public Host setName(String name) {
        getData().name = name;
        return self();
    }

    public InetAddress[] getAddresses() {
        return getData().addresses;
    }

    public boolean getIsAlive() {
        return isAlive;
    }

    public Host setIsAlive(boolean isAlive) {
        this.isAlive = isAlive;
        return self();
    }

    public Host setAddresses(InetAddress[] addresses) {
        getData().addresses = addresses;
        return self();
    }

    public Host setTunnelZones(Set<UUID> tunnelZones) {
        getData().tunnelZones = tunnelZones;
        return self();
    }

    public Set<UUID> getTunnelZones() {
        return getData().tunnelZones;
    }

    /**
     * Get the flooding proxy weight.
     *
     * This value defines the preference for this host to be chosen as a
     * VTEP's flooding proxy. The higher the value, the higher the probability.
     * A zero value prevents this host from being chosen.
     * @return a non-negative int value representing the host's weight.
     */
    public int getFloodingProxyWeight() {
        return this.floodingProxyWeight;
    }

    /**
     * Set the flooding proxy weight.
     *
     * This is needed when a Host object is being created, as the flooding
     * proxy weight is not part of the common Host.Data
     */
    public Host setFloodingProxyWeight(int weight) {
        this.floodingProxyWeight = weight;
        return self();
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
