/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class VIP {

    public UUID id;

    @JsonProperty("tenant_id")
    public String tenantId;

    public String name;

    public String description;

    @JsonProperty("networkId")
    public UUID networkId;

    public String address;

    public int port;

    @JsonProperty("lb_method")
    public String lbMethod;

    public String protocol;

    @JsonProperty("pool_id")
    public UUID poolId;

    @JsonProperty("session_persistence")
    public UUID sessionPersistence;

    @JsonProperty("connection_limit")
    public int connectionLimit;

    @JsonProperty("admin_state_up")
    public String adminStateUp;

    public String status;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof VIP)) return false;
        final VIP other = (VIP) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(name, other.name)
                && Objects.equal(description, other.description)
                && Objects.equal(networkId, other.networkId)
                && Objects.equal(address, other.address)
                && Objects.equal(port, other.port)
                && Objects.equal(lbMethod, other.lbMethod)
                && Objects.equal(protocol, other.protocol)
                && Objects.equal(poolId, other.poolId)
                && Objects.equal(sessionPersistence, other.sessionPersistence)
                && Objects.equal(connectionLimit, other.connectionLimit)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(status, other.status);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, name, description, networkId,
                address, port, lbMethod, protocol, poolId, sessionPersistence,
                connectionLimit, adminStateUp, status);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("tenantId", tenantId)
                .add("name", name)
                .add("description", description)
                .add("networkId", networkId)
                .add("address", address)
                .add("port", port)
                .add("lbMethod", lbMethod)
                .add("protocol", protocol)
                .add("poolId", poolId)
                .add("sessionPersistence", sessionPersistence)
                .add("connectionLimit", connectionLimit)
                .add("admin_state_up", adminStateUp)
                .add("status", status)
                .toString();
    }
}
