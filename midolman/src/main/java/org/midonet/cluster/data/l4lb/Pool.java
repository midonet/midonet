/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolProtocol;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;

import java.util.UUID;

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
