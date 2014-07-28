/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.UUID;

import com.google.common.base.Objects;

public class PoolHealthMonitor {

    public UUID poolId;

    public HealthMonitor healthMonitor;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) {
            return true;
        }

        if (!(obj instanceof PoolHealthMonitor)) {
            return false;
        }
        final PoolHealthMonitor other = (PoolHealthMonitor) obj;

        return Objects.equal(poolId, other.poolId)
               && Objects.equal(healthMonitor, other.healthMonitor);
    }

    @Override
    public final int hashCode() {
        return Objects.hashCode(poolId, healthMonitor);
    }

    @Override
    public final String toString() {
        return Objects.toStringHelper(this)
            .add("poolId", poolId)
            .toString();
    }
}
