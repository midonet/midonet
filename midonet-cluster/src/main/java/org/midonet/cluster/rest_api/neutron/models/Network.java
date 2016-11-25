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

@ZoomClass(clazz = Neutron.NeutronNetwork.class)
public class Network extends ZoomObject {

    public Network() {}

    public Network(UUID id, String tenantId, String name, boolean external) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
        this.shared = false;
        this.adminStateUp = true;
        this.external = external;
    }

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "status")
    public String status;

    @ZoomField(name = "shared")
    public boolean shared;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("router:external")
    @ZoomField(name = "external")
    public boolean external;

    @JsonProperty("provider:network_type")
    @ZoomField(name = "network_type")
    public NetworkType networkType;

    @JsonProperty("qos_policy_id")
    @ZoomField(name = "qos_policy_id")
    public UUID qosPolicyId;

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
                && Objects.equal(external, other.external)
                && Objects.equal(networkType, other.networkType)
                && Objects.equal(qosPolicyId, other.qosPolicyId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, status, shared, tenantId,
                adminStateUp, external, networkType, qosPolicyId);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("status", status)
                .add("shared", shared)
                .add("tenantId", tenantId)
                .add("adminStateUp", adminStateUp)
                .add("external", external)
                .add("networkType", networkType)
                .add("qosPolicyId", qosPolicyId)
                .toString();
    }
}
