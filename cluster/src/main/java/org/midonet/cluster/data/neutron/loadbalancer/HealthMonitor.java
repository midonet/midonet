/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class HealthMonitor {

    public UUID id;

    public String type;

    public int delay;

    public int timeout;

    public int attemptsBeforeDeactivation;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    public String status;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof HealthMonitor)) return false;
        final HealthMonitor other = (HealthMonitor) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(type, other.type)
                && Objects.equal(delay, other.delay)
                && Objects.equal(timeout, other.timeout)
                && Objects.equal(attemptsBeforeDeactivation,
                                 other.attemptsBeforeDeactivation)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(status, other.status);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, type, delay, timeout,
                attemptsBeforeDeactivation, adminStateUp, status);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("type", type)
                .add("delay", delay)
                .add("timeout", timeout)
                .add("attemptsBeforeDeactivation", attemptsBeforeDeactivation)
                .add("adminStateUp", adminStateUp)
                .add("status", status)
                .toString();
    }
}
