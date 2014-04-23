/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class RouterInterface {

    public UUID id;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("port_id")
    public UUID portId;

    @JsonProperty("subnet_id")
    public UUID subnetId;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof RouterInterface)) return false;
        final RouterInterface other = (RouterInterface) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(portId, other.portId)
                && Objects.equal(subnetId, other.subnetId)
                && Objects.equal(tenantId, other.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, portId, subnetId, tenantId);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("id", id)
                .add("portId", portId)
                .add("subnetId", subnetId)
                .add("tenantId", tenantId).toString();
    }
}
