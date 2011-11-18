/*
 * @(#)Tenant        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class Tenant extends ResourceDao {

    private String id = null;
    private URI routers = null;
    private URI bridges = null;

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
     * @return the bridges
     */
    public URI getBridges() {
        return bridges;
    }

    /**
     * @param bridges
     *            the bridges to set
     */
    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setBridges(String uri) throws URISyntaxException {
        this.bridges = new URI(uri);
    }

    /**
     * @return the routers
     */
    public URI getRouters() {
        return routers;
    }

    /**
     * @param routers
     *            the routers to set
     */
    public void setRouters(URI routers) {
        this.routers = routers;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setRouters(String uri) throws URISyntaxException {
        this.routers = new URI(uri);
    }

}
