/*
 * @(#)Tenant        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Tenant DTO.
 *
 * @version 1.6 20 Nov 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Tenant extends UriResource {

    private String id = null;

    /**
     * Constructor
     */
    public Tenant() {
        this(null);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID to set for the Tenant object.
     */
    public Tenant(String id) {
        super();
        this.id = id;
    }

    /**
     * Get tenant ID.
     *
     * @return Tenant ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Set tenant ID.
     *
     * @param id
     *            ID of the tenant.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the bridges URI
     */
    public URI getBridges() {
        return ResourceUriBuilder.getTenantBridges(getBaseUri(), id);
    }

    /**
     * @return the routers URI
     */
    public URI getRouters() {
        return ResourceUriBuilder.getTenantRouters(getBaseUri(), id);
    }

    /**
     * @return the routers URI
     */
    public URI getChains() {
        return ResourceUriBuilder.getTenantChains(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getTenant(getBaseUri(), id);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id;
    }
}
