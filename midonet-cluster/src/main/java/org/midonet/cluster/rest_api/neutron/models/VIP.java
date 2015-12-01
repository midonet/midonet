/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.neutron.models;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Neutron.NeutronVIP.class)
public class VIP extends ZoomObject {

    @ZoomField(name = "address", converter = IPAddressUtil.Converter.class)
    public String address;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("connection_limit")
    @ZoomField(name = "connection_limit")
    public int connectionLimit;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("pool_id")
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @JsonProperty("port_id")
    @ZoomField(name = "port_id")
    public UUID portId;

    @ZoomField(name = "protocol")
    public String protocol;

    @JsonProperty("protocol_port")
    @ZoomField(name = "protocol_port")
    public int protocolPort;

    @JsonProperty("session_persistence")
    @ZoomField(name = "session_persistence")
    public SessionPersistence sessionPersistence;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("status_description")
    @ZoomField(name = "status_description")
    public String statusDescription;

    @JsonProperty("subnet_id")
    @ZoomField(name = "subnet_id")
    public UUID subnetId;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
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
        return MoreObjects.toStringHelper(this)
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
