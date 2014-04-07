/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;
import org.midonet.midolman.state.LBStatus;

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

    public PoolMember setStatus(LBStatus status) {
        getData().status = status;
        return self();
    }

    public LBStatus getStatus() {
        return getData().status;
    }

    public PoolMember setPool(URI pool) {
        getData().pool = pool;
        return self();
    }

    public URI getPool() {
        return getData().pool;
    }

    public boolean isUp() {
        return getWeight() > 0 && getAdminStateUp() &&
               getStatus() == LBStatus.ACTIVE;
    }

    public static class Data {
        private UUID poolId;
        private String address;
        private int protocolPort;
        private int weight;
        private boolean adminStateUp = true;
        private LBStatus status;
        private URI pool;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return  Objects.equal(poolId, data.poolId) &&
                    Objects.equal(address, data.address) &&
                    protocolPort == data.protocolPort &&
                    weight == data.weight &&
                    adminStateUp == data.adminStateUp &&
                    status == data.status;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(poolId, address, protocolPort, weight,
                    adminStateUp, status);
        }
    }
}
