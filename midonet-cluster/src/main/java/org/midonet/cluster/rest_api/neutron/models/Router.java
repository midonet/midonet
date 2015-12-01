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
package org.midonet.cluster.rest_api.neutron.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.util.collection.ListUtil;

@ZoomClass(clazz = Neutron.NeutronRouter.class)
public class Router extends ZoomObject {

    public Router() {}

    public Router(UUID id, String tenantId, String name, boolean adminStateUp,
                  UUID gwPortId, ExternalGatewayInfo gwInfo,
                  List<Route> routes) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.gwPortId = gwPortId;
        this.externalGatewayInfo = gwInfo;
        this.adminStateUp = adminStateUp;
        this.routes = routes;
    }

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @JsonProperty("gw_port_id")
    @ZoomField(name = "gw_port_id")
    public UUID gwPortId;

    @JsonProperty("external_gateway_info")
    @ZoomField(name = "external_gateway_info")
    public ExternalGatewayInfo externalGatewayInfo;

    @ZoomField(name = "routes")
    public List<Route> routes = new ArrayList<>();

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
                    externalGatewayInfo, other.externalGatewayInfo)
                && ListUtils.isEqualList(routes, other.routes);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, status, tenantId, adminStateUp,
                gwPortId, externalGatewayInfo, routes);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("status", status)
                .add("tenantId", tenantId)
                .add("adminStateUp", adminStateUp)
                .add("gwPortId", gwPortId)
                .add("externalGatewayInfo", externalGatewayInfo)
                .add("routes", ListUtil.toString(routes)).toString();
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
