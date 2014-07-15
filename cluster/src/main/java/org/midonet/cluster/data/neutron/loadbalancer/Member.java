/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class Member {

    public UUID id;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("pool_id")
    public String poolId;

    public String address;

    public int port;

    public int weight;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    public String status;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Member)) return false;
        final Member other = (Member) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(poolId, other.poolId)
                && Objects.equal(address, other.address)
                && Objects.equal(port, other.port)
                && Objects.equal(weight, other.weight)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(status, other.status);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, poolId, address,
                port, weight, adminStateUp, status);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("tenantId", tenantId)
                .add("poolId", poolId)
                .add("address", address)
                .add("port", port)
                .add("weight", weight)
                .add("adminStateUp", adminStateUp)
                .add("status", status)
                .toString();
    }
}
