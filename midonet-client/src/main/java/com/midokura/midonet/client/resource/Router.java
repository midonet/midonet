/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

public class Router extends ResourceBase<Router, DtoRouter> {


    public Router(WebResource resource, URI uriForCreation, DtoRouter r) {
        super(resource, uriForCreation, r,
                VendorMediaType.APPLICATION_ROUTER_JSON);
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
     * Gets ID of oubbound filter on this router.
     *
     * @return UUID of the outbound filter
     */
    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
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
     * Sets name for creation
     *
     * @param name
     * @return this
     */
    public Router name(String name) {
        principalDto.setName(name);
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

    /**
     * Gets ports under the router.
     *
     * @return collection of router ports
     */
    public ResourceCollection<RouterPort> getPorts() {
        return getChildResources(principalDto.getPorts(),
                VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                RouterPort.class,
                DtoRouterPort.class);
    }

    /**
     * Gets routes under the router.
     *
     * @return collection of routes
     */
    public ResourceCollection<Route> getRoutes() {
        return getChildResources(principalDto.getRoutes(),
                VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
                Route.class,
                DtoRoute.class);
    }

    /**
     * Gets peer ports under the router.
     *
     * @return collection of ports
     */
    public ResourceCollection<Port> getPeerPorts() {
        ResourceCollection<Port> peerPorts =
                new ResourceCollection<Port>(new ArrayList<Port>());

        DtoPort[] dtoPeerPorts = resource.get(principalDto.getPeerPorts(),
                DtoPort[].class,
                VendorMediaType.APPLICATION_PORT_COLLECTION_JSON);

        for (DtoPort pp : dtoPeerPorts) {
            System.out.println("pp in the bridge resource: " + pp);
            Port p = null;
            if (pp instanceof DtoLogicalRouterPort) {
                p = new RouterPort<DtoLogicalRouterPort>(resource,
                        principalDto.getPorts(), (DtoLogicalRouterPort) pp);
            } else if (pp instanceof DtoLogicalBridgePort) {
                p = new BridgePort<DtoLogicalBridgePort>(resource,
                        principalDto.getPorts(), (DtoLogicalBridgePort) pp);

            }
            peerPorts.add(p);
        }
        return peerPorts;
    }

    /**
     * Returns materialized port resource for creation.
     *
     * @return materialized port resource
     */
    public RouterPort addMaterializedRouterPort() {
        return new RouterPort<DtoMaterializedRouterPort>(resource,
                principalDto.getPorts(), new DtoMaterializedRouterPort());
    }

    /**
     * Returns logical port resource for creation.
     *
     * @return logical port resource
     */

    public RouterPort addLogicalRouterPort() {
        return new RouterPort<DtoLogicalRouterPort>(resource,
                principalDto.getPorts(), new DtoLogicalRouterPort());
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
