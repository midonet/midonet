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

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.NeutronRouterInterface.class)
public class RouterInterface extends ZoomObject {

    public RouterInterface() {}

    public RouterInterface(UUID id, String tenantId, UUID portId,
                           UUID subnetId, List<UUID> subnetIds) {
        this.id = id;
        this.tenantId = tenantId;
        this.portId = portId;
        this.subnetId = subnetId;
        this.subnetIds = subnetIds;
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("port_id")
    @ZoomField(name = "port_id")
    public UUID portId;

    @JsonProperty("subnet_id")
    @ZoomField(name = "subnet_id")
    public UUID subnetId;

    @JsonProperty("subnet_ids")
    @ZoomField(name = "subnet_ids")
    public List<UUID> subnetIds;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof RouterInterface)) return false;
        final RouterInterface other = (RouterInterface) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(portId, other.portId)
                && Objects.equal(subnetId, other.subnetId)
                && Objects.equal(subnetIds, other.subnetIds)
                && Objects.equal(tenantId, other.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, portId, subnetId, subnetIds, tenantId);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("portId", portId)
                .add("subnetId", subnetId)
                .add("subnetIds", subnetId)
                .add("tenantId", tenantId).toString();
    }
}
