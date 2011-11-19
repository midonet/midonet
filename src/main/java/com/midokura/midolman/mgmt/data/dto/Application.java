/*
 * @(#)Application        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.UriManager;

/**
 * Application DTO.
 * 
 * @version 1.6 20 Nov 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Application extends UriResource {

    private String version = null;

    /**
     * Constructor
     * 
     * @param baseUri
     *            The base URI to construct all the URIs from.
     */
    public Application(URI baseUri) {
        super(baseUri);
    }

    /**
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @param version
     *            the version to set
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @return the tenants URI
     */
    public URI getTenant() {
        return UriManager.getTenants(getBaseUri());
    }

    /**
     * @return the admin URI
     */
    public URI getAdmin() {
        return UriManager.getAdmin(getBaseUri());
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getRoot(getBaseUri());
    }
}
