/*
 * @(#)Router        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;

/**
 * Class representing Virtual Router.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Router extends ResourceDao {

    private UUID id = null;
    private String name = null;
    private String tenantId = null;
    private URI ports = null;
    private URI chains = null;
    private URI routes = null;
    private URI peerRouters = null;

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
     * @return the ports
     */
    public URI getPorts() {
        return ports;
    }

    /**
     * @param ports
     *            the ports to set
     */
    public void setPorts(URI ports) {
        this.ports = ports;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setPorts(String uri) throws URISyntaxException {
        this.ports = new URI(uri);
    }

    /**
     * @return the chains
     */
    public URI getChains() {
        return chains;
    }

    /**
     * @param chains
     *            the chains to set
     */
    public void setChains(URI chains) {
        this.chains = chains;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setChains(String uri) throws URISyntaxException {
        this.chains = new URI(uri);
    }

    /**
     * @return the routes
     */
    public URI getRoutes() {
        return routes;
    }

    /**
     * @param routes
     *            the routes to set
     */
    public void setRoutes(URI routes) {
        this.routes = routes;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setRoutes(String uri) throws URISyntaxException {
        this.routes = new URI(uri);
    }

    /**
     * @return the peerRouters
     */
    public URI getPeerRouters() {
        return peerRouters;
    }

    /**
     * @param peerRouter
     *            the peerRouter to set
     */
    public void setPeerRouters(URI peerRouters) {
        this.peerRouters = peerRouters;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setPeerRouters(String uri) throws URISyntaxException {
        this.peerRouters = new URI(uri);
    }

    public RouterMgmtConfig toMgmtConfig() {
        return new RouterMgmtConfig(this.getTenantId(), this.getName());
    }

    public static Router createRouter(UUID id, RouterMgmtConfig config) {
        Router router = new Router();
        router.setName(config.name);
        router.setTenantId(config.tenantId);
        router.setId(id);
        return router;
    }

    public RouterNameMgmtConfig toNameMgmtConfig() {
        return new RouterNameMgmtConfig(this.getId());
    }
}
