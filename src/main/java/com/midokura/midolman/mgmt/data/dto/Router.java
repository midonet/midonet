/*
 * @(#)Router        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;

/**
 * Class representing Virtual Router.
 *
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Router extends UriResource {

    private UUID id = null;
    private String name = null;
    private String tenantId = null;

    /**
     * Constructor.
     */
    public Router() {
        this(null, null, null);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the router
     * @param config
     *            RouterMgmtConfig object.
     */
    public Router(UUID id, RouterMgmtConfig config) {
        this(id, config.name, config.tenantId);
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

    /**
     * @return the ports URI.
     */
    public URI getPorts() {
        return UriManager.getRouterPorts(getBaseUri(), id);
    }

    /**
     * @return the chains URI.
     */
    public URI getChains() {
        return UriManager.getRouterChains(getBaseUri(), id);
    }

    /**
     * @return the routes URI.
     */
    public URI getRoutes() {
        return UriManager.getRouterRoutes(getBaseUri(), id);
    }

    /**
     * @return the peerRouters URI.F
     */
    public URI getPeerRouters() {
        return UriManager.getRouterRouters(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getRouter(getBaseUri(), id);
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
