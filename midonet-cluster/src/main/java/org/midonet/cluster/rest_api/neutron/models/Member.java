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

@ZoomClass(clazz = Neutron.NeutronLoadBalancerPoolMember.class)
public class Member extends ZoomObject {

    @ZoomField(name = "address", converter = IPAddressUtil.Converter.class)
    public String address;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("pool_id")
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @JsonProperty("protocol_port")
    @ZoomField(name = "protocol_port")
    public int protocolPort;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("status_description")
    @ZoomField(name = "status_description")
    public String statusDescription;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "weight")
    public int weight;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Member)) return false;
        final Member other = (Member) obj;

        return Objects.equal(address, other.address)
               && Objects.equal(adminStateUp, other.adminStateUp)
               && Objects.equal(id, other.id)
               && Objects.equal(poolId, other.poolId)
               && Objects.equal(protocolPort, other.protocolPort)
               && Objects.equal(status, other.status)
               && Objects.equal(statusDescription, other.statusDescription)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(weight, other.weight);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(address, adminStateUp, id, poolId, protocolPort,
                                status, statusDescription, tenantId, weight);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("address", address)
            .add("adminStateUp", adminStateUp)
            .add("id", id)
            .add("poolId", poolId)
            .add("protocolPort", protocolPort)
            .add("status", status)
            .add("statusDescription", statusDescription)
            .add("tenantId", tenantId)
            .add("weight", weight)
            .toString();
    }
}
