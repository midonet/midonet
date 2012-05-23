/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Application DTO.
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
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTenants(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the VIFs URI
     */
    public URI getVifs() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getVifs(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the hosts URI
     */
    public URI getHosts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHosts(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRoot(getBaseUri());
        } else {
            return null;
        }
    }
}
