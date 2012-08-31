/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.resource;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.*;

import java.net.URI;

public class Tenant extends ResourceBase<Tenant, DtoTenant> {

    public Tenant(WebResource resource, URI uriForCreation, DtoTenant t) {
        super(resource, uriForCreation, t,
                VendorMediaType.APPLICATION_TENANT_JSON);
    }

    /**
     * Gets URI for this tenant.
     *
     * @return
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets string ID for the tenant
     *
     * @return
     */
    public String getId() {
        return principalDto.getId();
    }

    /**
     * Sets id to the DTO object.
     *
     * @param id
     * @return this
     */
    public Tenant id(String id) {
        principalDto.setId(id);
        return this;
    }

    /**
     * Gets bridges under the tenant.
     *
     * @return Collection of bridges
     */
    public ResourceCollection<Bridge> getBridges() {
        return getChildResources(principalDto.getBridges(),
                VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                Bridge.class, DtoBridge.class);
    }

    /**
     * Gets routers under the tenant.
     *
     * @return collection of routers
     */
    public ResourceCollection<Router> getRouters() {
        return getChildResources(principalDto.getRouters(),
                VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                Router.class, DtoRouter.class);
    }

    /**
     * Gets chains under the tenant.
     *
     * @return collection of chains
     */
    public ResourceCollection<RuleChain> getChains() {
        return getChildResources(principalDto.getChains(),
                VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
                RuleChain.class, DtoRuleChain.class);
    }

    /**
     * Gets port groups under the tenant.
     *
     * @return collection of port groups
     */
    public ResourceCollection<PortGroup> getPortGroups() {
        return getChildResources(principalDto.getPortGroups(),
                VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
                PortGroup.class, DtoPortGroup.class);
    }


    /**
     * Adds a bridge under this tenant.
     * @return new Bridge resource
     */
    public Bridge addBridge() {
        return new Bridge(resource, principalDto.getBridges(),
                new DtoBridge());
    }

    /**
     * Adds a router under this router.
     *
     * @return new Router() resource
     */
    public Router addRouter() {
        return new Router(resource, principalDto.getRouters(),
                new DtoRouter());
    }

    /**
     * Adds a chain under the tenant.
     * @return new Chain() resource
     */
    public RuleChain addChain() {
        return new RuleChain(resource, principalDto.getChains(),
                new DtoRuleChain());
    }

    /**
     * Adds a port group under the tenant
     *
     * @return new PortGroup() resource.
     */
    public PortGroup addPortGroup() {
        return new PortGroup(resource, principalDto.getPortGroups(),
                new DtoPortGroup());
    }

    @Override
    public String toString() {
        return String.format("Tenant{id=%s}", principalDto.getId());
    }
}
