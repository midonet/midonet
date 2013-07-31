/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.resource;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

import java.net.URI;

public class Tenant extends ResourceBase<Tenant, DtoTenant> {

    public Tenant(WebResource resource, URI uriForCreation, DtoTenant t) {
        super(resource, uriForCreation, t,
              VendorMediaType.APPLICATION_TENANT_JSON);
    }

    /**
     * Gets URI of this resource
     *
     * @return URI of this resource
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets ID of this resource
     *
     * @return String
     */
    public String getId() {
        return principalDto.getId();
    }

    /**
     * Gets name of the tenant
     *
     * @return name
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Sets name to the DTO.
     *
     * @param name
     * @return this
     */
    public Tenant name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Returns collection of bridges under the tenant
     *
     * @return collection of bridges
     */
    public ResourceCollection<Bridge> getBridges() {
        return getChildResources(
            principalDto.getBridges(),
            null,
            VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
            Bridge.class, DtoBridge.class);
    }

    /**
     * Returns collection of port groups under the tenant
     *
     * @return collection of port groups
     */
    public ResourceCollection<PortGroup> getPortGroups() {
        return getChildResources(
                principalDto.getPortGroups(),
                null,
                VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON,
                PortGroup.class, DtoPortGroup.class);
    }

    /**
     * Returns collection of routers under the tenant
     *
     * @return collection of routers
     */
    public ResourceCollection<Router> getRouters() {
        return getChildResources(
                principalDto.getRouters(),
                null,
                VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                Router.class, DtoRouter.class);
    }

    /**
     * Returns collection of chains under the tenant
     *
     * @return collection of chains
     */
    public ResourceCollection<RuleChain> getRuleChains() {
        return getChildResources(
                principalDto.getChains(),
                null,
                VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
                RuleChain.class, DtoRuleChain.class);
    }


    @Override
    public String toString() {
        return String.format("Tenant{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
