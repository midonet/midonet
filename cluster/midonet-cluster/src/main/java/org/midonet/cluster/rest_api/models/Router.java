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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.util.version.Since;

@ZoomClass(clazz = Topology.Router.class)
public class Router extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "inbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID inboundFilterId;
    @ZoomField(name = "outbound_filter_id", converter = UUIDUtil.Converter.class)
    public UUID outboundFilterId;

    @Since("2")
    @ZoomField(name = "load_balancer_id", converter = UUIDUtil.Converter.class)
    public UUID loadBalancerId;

    @JsonIgnore
    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portIds;

    @JsonIgnore
    @ZoomField(name = "route_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> routeIds;

    public Router() {
        adminStateUp = true;
    }

    public Router(URI baseUri) {
        adminStateUp = true;
        setBaseUri(baseUri);
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.ROUTERS, id);
    }

    public URI getInboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, inboundFilterId);
    }

    public URI getOutboundFilter() {
        return absoluteUri(ResourceUris.CHAINS, outboundFilterId);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    public URI getPeerPorts() {
        return relativeUri(ResourceUris.PEER_PORTS);
    }

    public URI getRoutes() {
        return relativeUri(ResourceUris.ROUTES);
    }

    @Since("2")
    public URI getLoadBalancer() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS, loadBalancerId);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(Router from) {
        this.id = from.id;
        portIds = from.portIds;
        routeIds = from.routeIds;
    }

}
