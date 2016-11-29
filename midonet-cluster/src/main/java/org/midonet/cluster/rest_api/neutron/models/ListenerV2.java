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

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2Listener.class)
public class ListenerV2 extends ZoomObject {

    @JsonProperty("id")
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("loadbalancer_id")
    @ZoomField(name = "loadbalancer_id")
    public UUID loadBalancerId;

    @JsonProperty("default_pool_id")
    @ZoomField(name = "default_pool_id")
    public UUID defaultPoolId;

    @JsonProperty("protocol_port")
    @ZoomField(name = "protocol_port")
    public Integer protocolPort;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("protocolPort", protocolPort)
                .add("adminStateUp", adminStateUp)
                .add("loadBalancerId", loadBalancerId)
                .add("defaultPoolId", defaultPoolId)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ListenerV2 that = (ListenerV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(protocolPort, that.protocolPort) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                Objects.equal(loadBalancerId, that.loadBalancerId) &&
                Objects.equal(defaultPoolId, that.defaultPoolId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, protocolPort, adminStateUp,
                                loadBalancerId, defaultPoolId);
    }
}
