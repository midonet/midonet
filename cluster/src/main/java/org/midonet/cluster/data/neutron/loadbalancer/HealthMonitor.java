/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

public class HealthMonitor {

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    public int delay;

    public UUID id;

    @JsonProperty("max_retries")
    public int maxRetries;

    public List<HealthMonitorPool> pools;

    @JsonProperty("tenant_id")
    public String tenantId;

    public int timeout;

    public String type;

    @JsonIgnore
    public void removePool(UUID poolId) {
        for (Iterator<HealthMonitorPool> it = pools.iterator(); it.hasNext();) {
            if (Objects.equal(it.next().poolId, poolId)) {
                it.remove();
                return;
            }
        }
    }

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) {
            return true;
        }

        if (!(obj instanceof HealthMonitor)) {
            return false;
        }
        final HealthMonitor other = (HealthMonitor) obj;

        return Objects.equal(adminStateUp, other.adminStateUp)
               && Objects.equal(delay, other.delay)
               && Objects.equal(id, other.id)
               && Objects.equal(maxRetries, other.maxRetries)
               && Objects.equal(pools, other.pools)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(timeout, other.timeout)
               && Objects.equal(type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(adminStateUp, delay, id, maxRetries, pools,
                                tenantId, timeout, type);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("adminStateUp", adminStateUp)
            .add("delay", delay)
            .add("id", id)
            .add("maxRetries", maxRetries)
            .add("pools", pools)
            .add("tenantId", tenantId)
            .add("timeout", timeout)
            .add("type", type)
            .toString();
    }

    @JsonIgnore
    public void addPool(UUID poolId) {
        HealthMonitorPool pool = new HealthMonitorPool();
        pool.poolId = poolId;
        if (pools == null) {
            pools = new ArrayList<>();
        }
        pools.add(pool);
    }

    @JsonIgnore
    public boolean hasPoolAssociated() {
        return pools != null && pools.size() > 0;
    }

    @JsonIgnore
    public UUID getPoolId() {
        return hasPoolAssociated() ? pools.get(0).poolId : null;
    }

    /*
     * This class represents the trimmed down pool class that comes serialized
     * with a HealthMonitor. It represents the pools that are using this
     * health monitor. It is *NOT* related to the PoolHealthMonitor class in
     * any way.
     */
    public static class HealthMonitorPool {

        @JsonProperty("pool_id")
        public UUID poolId;

        public String status;

        @JsonProperty("status_description")
        public String statusDescription;

        @Override
        public final boolean equals(Object obj) {

            if (obj == this) {
                return true;
            }

            if (!(obj instanceof HealthMonitor)) {
                return false;
            }
            final HealthMonitorPool other = (HealthMonitorPool) obj;

            return Objects.equal(poolId, other.poolId)
                   && Objects.equal(status, other.status)
                   && Objects.equal(statusDescription, other.statusDescription);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(poolId, status, statusDescription);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("poolId", poolId)
                .add("status", status)
                .add("statusDescription", statusDescription)
                .toString();
        }
    }
}
