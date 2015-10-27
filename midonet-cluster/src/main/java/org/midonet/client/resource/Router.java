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

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTE_COLLECTION_JSON;

public class Router extends ResourceBase<Router, DtoRouter> {


    public Router(WebResource resource, URI uriForCreation, DtoRouter r) {
        super(resource, uriForCreation, r,
              APPLICATION_ROUTER_JSON_V3());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
    }

    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    public UUID getLoadBalancerId() {
        return principalDto.getLoadBalancerId();
    }

    public String getTenantId() {
        return principalDto.getTenantId();
    }

    public Router name(String name) {
        principalDto.setName(name);
        return this;
    }

    public Router adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    public Router tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }

    public Router outboundFilterId(UUID outboundFilterId) {
        principalDto.setOutboundFilterId(outboundFilterId);
        return this;
    }

    public Router inboundFilterId(UUID inboundFilterId) {
        principalDto.setInboundFilterId(inboundFilterId);
        return this;
    }

    public Router loadBalancerId(UUID loadBalancerId) {
        principalDto.setLoadBalancerId(loadBalancerId);
        return this;
    }

    public ResourceCollection<RouterPort> getPorts(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(
                principalDto.getPorts(),
                queryParams,
                APPLICATION_PORT_V3_COLLECTION_JSON(),
                RouterPort.class,
                DtoRouterPort.class);
    }

    public ResourceCollection<Route> getRoutes(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(
            principalDto.getRoutes(),
            queryParams,
            APPLICATION_ROUTE_COLLECTION_JSON(),
            Route.class,
            DtoRoute.class);
    }

    public ResourceCollection<Port<?,?>> getPeerPorts(
            MultivaluedMap<String,String> queryParams) {
        ResourceCollection<Port<?,?>> peerPorts =
                new ResourceCollection<>(new ArrayList<Port<?,?>>());

        DtoPort[] dtoPeerPorts = resource
                .get(principalDto.getPeerPorts(),
                        queryParams,
                        DtoPort[].class,
                        APPLICATION_PORT_V3_COLLECTION_JSON());

        for (DtoPort pp : dtoPeerPorts) {
            if (pp instanceof DtoRouterPort) {
                peerPorts.add(new RouterPort(
                    resource, principalDto.getPorts(), (DtoRouterPort) pp));
            } else if (pp instanceof DtoBridgePort) {
                peerPorts.add(new BridgePort(
                    resource, principalDto.getPorts(), (DtoBridgePort) pp));

            }
        }
        return peerPorts;
    }

    public RouterPort addPort() {
        return new RouterPort(
                resource,
                principalDto.getPorts(),
                new DtoRouterPort());
    }

    public Route addRoute() {
        return new Route(resource, principalDto.getRoutes(), new DtoRoute());
    }

    @Override
    public String toString() {
        return String.format("Router{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
