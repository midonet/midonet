/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.UUID;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonProperty;

public class VIP {

    public String address;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("connection_limit")
    public int connectionLimit;

    public String description;

    public UUID id;

    public String name;

    @JsonProperty("pool_id")
    public UUID poolId;

    @JsonProperty("port_id")
    public UUID portId;

    public String protocol;

    @JsonProperty("protocol_port")
    public int protocolPort;

    @JsonProperty("session_persistence")
    public SessionPersistence sessionPersistence;

    public String status;

    @JsonProperty("status_description")
    public String statusDescription;

    @JsonProperty("subnet_id")
    public UUID subnetId;

    @JsonProperty("tenant_id")
    public String tenantId;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof VIP)) return false;
        final VIP other = (VIP) obj;

        return Objects.equal(address, other.address)
               && Objects.equal(adminStateUp, other.adminStateUp)
               && Objects.equal(connectionLimit, other.connectionLimit)
               && Objects.equal(description, other.description)
               && Objects.equal(id, other.id)
               && Objects.equal(name, other.name)
               && Objects.equal(poolId, other.poolId)
               && Objects.equal(portId, other.portId)
               && Objects.equal(protocol, other.protocol)
               && Objects.equal(protocolPort, other.protocolPort)
               && Objects.equal(sessionPersistence, other.sessionPersistence)
               && Objects.equal(status, other.status)
               && Objects.equal(statusDescription, other.statusDescription)
               && Objects.equal(subnetId, other.subnetId)
               && Objects.equal(tenantId, other.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(address, adminStateUp, connectionLimit,
                                description, id, name, poolId, portId, protocol,
                                protocolPort, sessionPersistence, status,
                                statusDescription, subnetId, tenantId);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("address", address)
            .add("adminStateUp", adminStateUp)
            .add("connectionLimit", connectionLimit)
            .add("description", description)
            .add("id", id)
            .add("name", name)
            .add("poolId", poolId)
            .add("portId", portId)
            .add("protocol", protocol)
            .add("protocolPort", protocolPort)
            .add("sessionPersistence", sessionPersistence)
            .add("status", status)
            .add("statusDescription", statusDescription)
            .add("subnetId", subnetId)
            .add("tenantId", tenantId)
            .toString();
    }
}
