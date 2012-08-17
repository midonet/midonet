/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;

/**
 * Tenant DTO.
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
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTenantBridges(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the routers URI
     */
    public URI getRouters() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTenantRouters(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the URI for the Tenant's chains resource.
     */
    public URI getChains() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTenantChains(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the URI for the Tenant's chains resource.
     */
    public URI getPortGroups() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTenantPortGroups(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTenant(getBaseUri(), id);
        } else {
            return null;
        }
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
