/*
 * @(#)Tenant        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.UriManager;

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
        return UriManager.getTenantBridges(getBaseUri(), id);
    }

    /**
     * @return the routers URI
     */
    public URI getRouters() {
        return UriManager.getTenantRouters(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getTenant(getBaseUri(), id);
    }
}
