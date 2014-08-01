/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonProperty;

public class HealthMonitor {

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    public int delay;

    public UUID id;

    @JsonProperty("max_retries")
    public int maxRetries;

    public List<Pool> pools;

    @JsonProperty("tenant_id")
    public String tenantId;

    public int timeout;

    public String type;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof HealthMonitor)) return false;
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
}
