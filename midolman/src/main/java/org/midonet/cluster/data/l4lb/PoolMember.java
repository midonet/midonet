/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;

import java.net.URI;
import java.util.UUID;

public class PoolMember extends Entity.Base<UUID, PoolMember.Data, PoolMember>{

    public PoolMember() {
        this(null, new Data());
    }

    public PoolMember(UUID id){
        this(id, new Data());
    }

    public PoolMember(Data data){
        this(null, data);
    }

    public PoolMember(UUID uuid, Data data) {
        super(uuid, data);
    }

    protected PoolMember self() {
        return this;
    }

    public PoolMember setPoolId(UUID poolId) {
        getData().poolId = poolId;
        return self();
    }

    public UUID getPoolId() {
        return getData().poolId;
    }

    public PoolMember setAddress(String address) {
        getData().address = address;
        return self();
    }

    public String getAddress() {
        return getData().address;
    }

    public PoolMember setProtocolPort(int protocolPort) {
        getData().protocolPort = protocolPort;
        return self();
    }

    public int getProtocolPort() {
        return getData().protocolPort;
    }

    public PoolMember setWeight(int weight) {
        getData().weight = weight;
        return self();
    }

    public int getWeight() {
        return getData().weight;
    }

    public PoolMember setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean getAdminStateUp() {
        return getData().adminStateUp;
    }

    public PoolMember setStatus(String status) {
        getData().status = status;
        return self();
    }

    public String getStatus() {
        return getData().status;
    }

    public PoolMember setPool(URI pool) {
        getData().pool = pool;
        return self();
    }

    public URI getPool() {
        return getData().pool;
    }

    public static class Data {
        private UUID poolId;
        private String address;
        private int protocolPort;
        private int weight;
        private boolean adminStateUp = true;
        private String status;
        private URI pool;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (!Objects.equal(poolId, data.poolId)) return false;
            if (!Objects.equal(address, data.address)) return false;
            if (protocolPort != data.protocolPort) return false;
            if (weight != data.weight) return false;
            if (adminStateUp != data.adminStateUp) return false;
            if (!Objects.equal(status, data.status)) return false;
            if (!Objects.equal(pool, data.pool)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = poolId != null ? poolId.hashCode() : 0;
            result = 31 * result + (address != null ? address.hashCode() : 0);
            result = 31 * result + protocolPort;
            result = 31 * result + weight;
            result = 31 * result + (adminStateUp ? 1 : 0);
            result = 31 * result + (status != null ? status.hashCode() : 0);
            result = 31 * result + (pool != null ? pool.hashCode() : 0);
            return result;
        }
    }
}
