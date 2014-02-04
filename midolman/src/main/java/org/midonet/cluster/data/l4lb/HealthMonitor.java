/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import org.midonet.cluster.data.Entity;

import java.util.UUID;

public class HealthMonitor
        extends Entity.Base<UUID, HealthMonitor.Data, HealthMonitor>{

    public HealthMonitor() {
        this(null, new Data());
    }

    public HealthMonitor(UUID id){
        this(id, new Data());
    }

    public HealthMonitor(Data data){
        this(null, data);
    }

    public HealthMonitor(UUID uuid, Data data) {
        super(uuid, data);
    }

    protected HealthMonitor self() {
        return this;
    }

    public HealthMonitor setType(String type) {
        getData().type = type;
        return self();
    }

    public String getType() {
        return getData().type;
    }

    public HealthMonitor setPoolId(UUID poolId) {
        getData().poolId = poolId;
        return self();
    }

    public UUID getPoolId() {
        return getData().poolId;
    }

    public HealthMonitor setDelay(int delay) {
        getData().delay = delay;
        return self();
    }

    public int getDelay() {
        return getData().delay;
    }

    public HealthMonitor setTimeout(int timeout) {
        getData().timeout = timeout;
        return self();
    }

    public int getTimeout() {
        return getData().timeout;
    }

    public HealthMonitor setMaxRetries(int maxRetries) {
        getData().maxRetries = maxRetries;
        return self();
    }

    public int getMaxRetries() {
        return getData().maxRetries;
    }

    public HealthMonitor setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean getAdminStateUp() {
        return getData().adminStateUp;
    }

    public static class Data {
        private String type;
        private UUID poolId;
        private int delay;
        private int timeout;
        private int maxRetries;
        private boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (adminStateUp != data.adminStateUp) return false;
            if (delay != data.delay) return false;
            if (maxRetries != data.maxRetries) return false;
            if (timeout != data.timeout) return false;
            if (poolId != null ? !poolId.equals(data.poolId)
                    : data.poolId != null) return false;
            if (type != null ? !type.equals(data.type)
                    : data.type != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = type != null ? type.hashCode() : 0;
            result = 31 * result + (poolId != null ? poolId.hashCode() : 0);
            result = 31 * result + delay;
            result = 31 * result + timeout;
            result = 31 * result + maxRetries;
            result = 31 * result + (adminStateUp ? 1 : 0);
            return result;
        }
    }
}
