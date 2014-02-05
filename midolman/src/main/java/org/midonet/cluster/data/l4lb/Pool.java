/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.l4lb;

import com.google.common.base.Objects;
import org.midonet.cluster.data.Entity;

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

    public Pool setName(String name) {
        getData().name = name;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public Pool setDescription(String description) {
        getData().description = description;
        return self();
    }

    public String getDescription() {
        return getData().description;
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

    public Pool setProtocol(String protocol) {
        getData().protocol = protocol;
        return self();
    }

    public String getProtocol() {
        return getData().protocol;
    }

    public Pool setLbMethod(String lbMethod) {
        getData().lbMethod = lbMethod;
        return self();
    }

    public String getLbMethod() {
        return getData().lbMethod;
    }

    public Pool setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public Pool setStatus(String status) {
        getData().status = status;
        return self();
    }

    public String getStatus() {
        return getData().status;
    }

    public static class Data {
        private String name;
        private String description;
        private UUID loadBalancerId;
        private UUID healthMonitorId;
        private String protocol;
        private String lbMethod;
        private boolean adminStateUp = true;
        private String status;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (!Objects.equal(name, data.name)) return false;
            if (!Objects.equal(description, data.description)) return false;
            if (!Objects.equal(loadBalancerId, data.loadBalancerId)) return false;
            if (!Objects.equal(healthMonitorId, data.healthMonitorId)) return false;
            if (!Objects.equal(protocol, data.protocol)) return false;
            if (!Objects.equal(lbMethod, data.lbMethod)) return false;
            if (adminStateUp != data.adminStateUp) return false;
            if (!Objects.equal(status, data.status)) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result
                    + (description != null ? description.hashCode() : 0);
            result = 31 * result
                    + (loadBalancerId != null ? loadBalancerId.hashCode() : 0);
            result = 31 * result
                    + (healthMonitorId != null ?
                       healthMonitorId.hashCode() : 0);
            result = 31 * result
                    + (protocol != null ? protocol.hashCode() : 0);
            result = 31 * result
                    + (lbMethod != null ? lbMethod.hashCode() : 0);
            result = 31 * result + (adminStateUp ? 1 : 0);
            result = 31 * result
                    + (status != null ? status.hashCode() : 0);
            return result;
        }
    }
}
