/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.boilerplate.l4lb;

import java.util.UUID;

import com.google.common.base.Objects;

import org.midonet.cluster.data.Entity;

public class Pool extends Entity.Base<UUID, Pool.Data, Pool>{

    /**
     * Globally unique ID used to represent a map of health monitor and pool
     * mappings.  The reason why we need this is because VTA and ClusterManager
     * requires that there is a key to retrieve data.
     */
    public static final UUID POOL_HEALTH_MONITOR_MAP_KEY = UUID.fromString(
                    "f7c96553-a9c6-48b7-933c-31563bf77952");


    public Pool() {
        this(null, new Data());
    }

    public Pool(UUID id){
        this(id, new Data());
    }

    public Pool(Data data){
        this(null, data);
    }

    public Pool(UUID uuid, Data data) {
        super(uuid, data);
    }

    protected Pool self() {
        return this;
    }

    public Pool setLoadBalancerId(UUID loadBalancerId) {
        getData().loadBalancerId = loadBalancerId;
        return self();
    }

    public UUID getLoadBalancerId() {
        return getData().loadBalancerId;
    }

    public Pool setHealthMonitorId(UUID healthMonitorId) {
        getData().healthMonitorId = healthMonitorId;
        return self();
    }

    public UUID getHealthMonitorId() {
        return getData().healthMonitorId;
    }

    public Pool setProtocol(PoolProtocol protocol) {
        getData().protocol = protocol;
        return self();
    }

    public PoolProtocol getProtocol() {
        return getData().protocol;
    }

    public Pool setLbMethod(PoolLBMethod lbMethod) {
        getData().lbMethod = lbMethod;
        return self();
    }

    public PoolLBMethod getLbMethod() {
        return getData().lbMethod;
    }

    public Pool setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public Pool setStatus(LBStatus status) {
        getData().status = status;
        return self();
    }

    public LBStatus getStatus() {
        return getData().status;
    }

    public Pool setMappingStatus(
            PoolHealthMonitorMappingStatus mappingStatus) {
        getData().mappingStatus = mappingStatus;
        return self();
    }

    public PoolHealthMonitorMappingStatus getMappingStatus() {
        return getData().mappingStatus;
    }

    public static class Data {
        private UUID loadBalancerId;
        private UUID healthMonitorId;
        private PoolProtocol protocol = PoolProtocol.TCP;
        private PoolLBMethod lbMethod = PoolLBMethod.ROUND_ROBIN;
        private boolean adminStateUp = true;
        private LBStatus status = LBStatus.ACTIVE;
        private PoolHealthMonitorMappingStatus mappingStatus =
                PoolHealthMonitorMappingStatus.INACTIVE;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return Objects.equal(loadBalancerId, data.loadBalancerId) &&
                    Objects.equal(healthMonitorId, data.healthMonitorId) &&
                    protocol == data.protocol &&
                    lbMethod == data.lbMethod &&
                    adminStateUp == data.adminStateUp &&
                    status == data.status &&
                    mappingStatus == data.mappingStatus;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerId, healthMonitorId, protocol,
                    lbMethod, status, mappingStatus);
        }

        @Override
        public String toString() {
            return "Data{" +
                    "loadBalancerId=" + loadBalancerId +
                    ", healthMonitorId=" + healthMonitorId +
                    ", protocol='" + protocol + '\'' +
                    ", lbMethod='" + lbMethod + '\'' +
                    ", adminStateUp=" + adminStateUp +
                    ", status=" + status +
                    ", mappingStatus=" + mappingStatus +
                    '}';
        }
    }
}
