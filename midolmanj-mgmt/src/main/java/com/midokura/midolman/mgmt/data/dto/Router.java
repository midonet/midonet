/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.RouterZkManager.RouterConfig;

/**
 * Class representing Virtual Router.
 */
@XmlRootElement
public class Router extends UriResource {

    private UUID id;
    private String name;
    private String tenantId;
    private UUID inboundFilter;
    private UUID outboundFilter;

    /**
     * Constructor.
     */
    public Router() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the router.
     * @param name
     *            Name of the router.
     * @param tenantId
     *            ID of the tenant that owns the router.
     */
    public Router(UUID id, String name, String tenantId) {
        super();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    /**
     * Get router ID.
     *
     * @return Router ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set router ID.
     *
     * @param id
     *            ID of the router.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get router name.
     *
     * @return Router name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set router name.
     *
     * @param name
     *            Name of the router.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get tenant ID.
     *
     * @return Tenant ID.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Set tenant ID.
     *
     * @param tenantId
     *            Tenant ID of the router.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
    }

    /**
     * @return the ports URI.
     */
    public URI getPorts() {
        return ResourceUriBuilder.getRouterPorts(getBaseUri(), id);
    }

    /**
     * @return the routes URI.
     */
    public URI getRoutes() {
        return ResourceUriBuilder.getRouterRoutes(getBaseUri(), id);
    }

    /**
     * @return the peerRouters URI.
     */
    public URI getPeerRouters() {
        return ResourceUriBuilder.getRouterRouters(getBaseUri(), id);
    }

    /**
     * @return the peerBridges URI.
     */
    public URI getPeerBridges() {
        return ResourceUriBuilder.getRouterBridges(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getRouter(getBaseUri(), id);
    }

    /**
     * Convert this object to RouterConfig object
     *
     * @return RouterConfig object
     */
    public RouterConfig toConfig() {
        return new RouterConfig(this.inboundFilter, this.outboundFilter);

    }

    /**
     * Convert this object to RouterMgmtConfig object.
     *
     * @return RouterMgmtConfig object.
     */
    public RouterMgmtConfig toMgmtConfig() {
        return new RouterMgmtConfig(this.getTenantId(), this.getName());
    }

    /**
     * Convert this object to RouterNameMgmtConfig object.
     *
     * @return RouterNameMgmtConfig object.
     */
    public RouterNameMgmtConfig toNameMgmtConfig() {
        return new RouterNameMgmtConfig(this.getId());
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", name=" + name + ", tenantId=" + tenantId;
    }

}
