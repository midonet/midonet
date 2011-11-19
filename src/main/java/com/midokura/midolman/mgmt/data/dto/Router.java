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
        return UriManager.getRouterPorts(getBaseUri(), this);
    }

    /**
     * @return the chains URI.
     */
    public URI getChains() {
        return UriManager.getRouterChains(getBaseUri(), this);
    }

    /**
     * @return the routes URI.
     */
    public URI getRoutes() {
        return UriManager.getRouterRoutes(getBaseUri(), this);
    }

    /**
     * @return the peerRouters URI.F
     */
    public URI getPeerRouters() {
        return UriManager.getRouterRouters(getBaseUri(), this);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getRouter(getBaseUri(), this);
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
     * Convert RouterMgmtConfig object to Router object.
     * 
     * @param id
     *            ID of the object.
     * @param config
     *            RouterMgmtConfig object.
     * @return Router object.
     */
    public static Router createRouter(UUID id, RouterMgmtConfig config) {
        Router router = new Router();
        router.setName(config.name);
        router.setTenantId(config.tenantId);
        router.setId(id);
        return router;
    }

    /**
     * Convert this object to RouterNameMgmtConfig object.
     * 
     * @return RouterNameMgmtConfig object.
     */
    public RouterNameMgmtConfig toNameMgmtConfig() {
        return new RouterNameMgmtConfig(this.getId());
    }
}
