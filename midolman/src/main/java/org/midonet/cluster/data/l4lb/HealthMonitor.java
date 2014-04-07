/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;
import org.midonet.midolman.state.LBStatus;

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

    public HealthMonitor setStatus(LBStatus status) {
        getData().status = status;
        return self();
    }

    public LBStatus getStatus() {
        return getData().status;
    }

    public static class Data {
        private String type;
        private int delay;
        private int timeout;
        private int maxRetries;
        private boolean adminStateUp = true;
        private LBStatus status;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return Objects.equal(type, data.type) &&
                    delay == data.delay &&
                    timeout == data.timeout &&
                    maxRetries == data.maxRetries &&
                    adminStateUp == data.adminStateUp &&
                    status == data.status;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(type, delay, timeout, maxRetries,
                    adminStateUp, status);
        }
    }
}
