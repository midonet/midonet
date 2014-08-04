/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class Network {

    public Network() {}

    public Network(UUID id, String tenantId, String name, boolean external) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
        this.shared = false;
        this.adminStateUp = true;
        this.external = external;
    }

    public UUID id;
    public String name;
    public String status;
    public boolean shared;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("router:external")
    public boolean external;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Network)) return false;
        final Network other = (Network) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(status, other.status)
                && Objects.equal(shared, other.shared)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(external, other.external);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, status, shared, tenantId,
                adminStateUp, external);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("status", status)
                .add("shared", shared)
                .add("tenantId", tenantId)
                .add("adminStateUp", adminStateUp)
                .add("external", external).toString();
    }
}
