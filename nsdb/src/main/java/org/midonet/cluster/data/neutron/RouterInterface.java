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
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class RouterInterface {

    public RouterInterface() {}

    public RouterInterface(UUID id, String tenantId, UUID portId,
                           UUID subnetId) {
        this.id = id;
        this.tenantId = tenantId;
        this.portId = portId;
        this.subnetId = subnetId;
    }

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
