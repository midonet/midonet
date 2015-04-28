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
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class Router {

    public Router() {}

    public Router(UUID id, String tenantId, String name, boolean adminStateUp,
                  UUID gwPortId, ExternalGatewayInfo gwInfo) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.gwPortId = gwPortId;
        this.externalGatewayInfo = gwInfo;
        this.adminStateUp = adminStateUp;
    }

    public UUID id;
    public String name;
    public String status;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("gw_port_id")
    public UUID gwPortId;

    @JsonProperty("external_gateway_info")
    public ExternalGatewayInfo externalGatewayInfo;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Router)) return false;
        final Router other = (Router) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(status, other.status)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(gwPortId, other.gwPortId)
                && Objects.equal(
                    externalGatewayInfo, other.externalGatewayInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, status, tenantId, adminStateUp,
                gwPortId, externalGatewayInfo);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("status", status)
                .add("tenantId", tenantId)
                .add("adminStateUp", adminStateUp)
                .add("gwPortId", gwPortId)
                .add("externalGatewayInfo", externalGatewayInfo).toString();
    }

    @JsonIgnore
    public String preRouteChainName() {
        if (id == null) return null;
        return "OS_PRE_ROUTING_" + id;
    }

    @JsonIgnore
    public String postRouteChainName() {
        if (id == null) return null;
        return "OS_POST_ROUTING_" + id;
    }

    @JsonIgnore
    public boolean snatEnabled() {
        return externalGatewayInfo != null && externalGatewayInfo.enableSnat;
    }

}
