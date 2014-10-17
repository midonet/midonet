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

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

public class Router extends ResourceBase<Router, DtoRouter> {


    public Router(WebResource resource, URI uriForCreation, DtoRouter r) {
        super(resource, uriForCreation, r,
              VendorMediaType.APPLICATION_ROUTER_JSON_V2);
    }

    /**
     * Gets URI for this router.
     *
     * @return URI for this resource
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets ID of this router.
     *
     * @return UUID of t
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets ID of the inbound filter on this router.
     *
     * @return UUID of the inbound filter
     */
    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    /**
     * Gets name of this router.
     *
     * @return name
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Get administrative state
     *
     * @return administrative state of the router.
     */

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
    }

    /**
     * Gets ID of outbound filter on this router.
     *
     * @return UUID of the outbound filter
     */
    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    /**
     * Gets ID of loadBalancer on this router.
     *
     * @return UUID of the loadBalancer
     */
    public UUID getLoadBalancerId() {
        return principalDto.getLoadBalancerId();
    }

    /**
     * Gets tenant ID for this router.
     *
     * @return tenant ID string
     */
    public String getTenantId() {
        return principalDto.getTenantId();
    }

    /**
     * Sets name.
     *
     * @param name
     * @return this
     */
    public Router name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Set administrative state
     *
     * @param adminStateUp
     *            administrative state of the router.
     */
    public Router adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    /**
     * Sets tenantID
     *
     * @param tenantId
     * @return this
     */
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

    /**
     * Gets V2 ports under the router.
     *
     * @return collection of router ports
     */
    public ResourceCollection<RouterPort> getPorts(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(
                principalDto.getPorts(),
                queryParams,
                VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON,
                RouterPort.class,
                DtoRouterPort.class);
    }

    /**
     * Gets routes under the router.
     *
     * @return collection of routes
     */
    public ResourceCollection<Route> getRoutes(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(
            principalDto.getRoutes(),
            queryParams,
            VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
            Route.class,
            DtoRoute.class);
    }

    /**
     * Gets peer ports under the router.
     *
     * @return collection of ports
     */
    public ResourceCollection<Port<?,?>> getPeerPorts(
            MultivaluedMap<String,String> queryParams) {
        ResourceCollection<Port<?,?>> peerPorts =
                new ResourceCollection<>(new ArrayList<Port<?,?>>());

        DtoPort[] dtoPeerPorts = resource
                .get(principalDto.getPeerPorts(),
                        queryParams,
                        DtoPort[].class,
                        VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON);

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

    /**
     * Returns Router port resource for creation.
     *
     * @return router port resource
     */
    public RouterPort addPort() {
        return new RouterPort(
                resource,
                principalDto.getPorts(),
                new DtoRouterPort());
    }

    /**
     * Returns route resource for creation.
     *
     * @return route resource
     */
    public Route addRoute() {
        return new Route(resource, principalDto.getRoutes(), new DtoRoute());
    }

    @Override
    public String toString() {
        return String.format("Router{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
