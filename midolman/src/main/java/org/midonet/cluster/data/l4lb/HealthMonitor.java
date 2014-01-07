/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
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

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public HealthMonitor setStatus(String status) {
        getData().status = status;
        return self();
    }

    public String getStatus() {
        return getData().status;
    }

    public static class Data {
        private String type;
        private int delay;
        private int timeout;
        private int maxRetries;
        private boolean adminStateUp = true;
        private String status;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (!Objects.equal(type, data.type)) return false;
            if (delay != data.delay) return false;
            if (timeout != data.timeout) return false;
            if (maxRetries != data.maxRetries) return false;
            if (adminStateUp != data.adminStateUp) return false;
            if (!Objects.equal(status, data.status)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = type != null ? type.hashCode() : 0;
            result = 31 * result + delay;
            result = 31 * result + timeout;
            result = 31 * result + maxRetries;
            result = 31 * result + (adminStateUp ? 1 : 0);
            result = 31 * result + (status != null ? status.hashCode() : 0);
            return result;
        }
    }
}
