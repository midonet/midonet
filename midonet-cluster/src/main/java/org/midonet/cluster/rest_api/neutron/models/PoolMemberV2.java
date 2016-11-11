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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;

import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerPoolMember.class)
public class PoolMemberV2 extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("project_id")
    @ZoomField(name = "project_id")
    public String projectId;

    @JsonProperty("pool_id")
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @ZoomField(name = "address", converter = IPAddressUtil.Converter.class)
    public Commons.IPAddress address;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("protocol_port")
    @ZoomField(name = "protocol_port")
    public Integer protocolPort;

    @ZoomField(name = "weight")
    public Integer weight;

    @ZoomField(name = "status")
    public Commons.LBStatus status;

    @JsonProperty("status_description")
    @ZoomField(name = "status_description")
    public String statusDescription;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("projectId", projectId)
                .add("poolId", poolId)
                .add("address", address)
                .add("adminStateUp", adminStateUp)
                .add("protocolPort", protocolPort)
                .add("weight", weight)
                .add("status", status)
                .add("statusDescription", statusDescription)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PoolMemberV2 that = (PoolMemberV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(projectId, that.projectId) &&
                Objects.equal(poolId, that.poolId) &&
                Objects.equal(address, that.address) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                Objects.equal(protocolPort, that.protocolPort) &&
                Objects.equal(weight, that.weight) &&
                status == that.status &&
                Objects.equal(statusDescription, that.statusDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, projectId, poolId, address,
                adminStateUp, protocolPort, weight, status, statusDescription);
    }
}
