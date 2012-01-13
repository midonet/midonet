package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlTransient;

public abstract class UriResource {

    private URI baseUri = null;

    /**
     * Default constructor
     */
    public UriResource() {
    }

    /**
     * Constructor
     *
     * @param baseUri
     *            The base URI to construct all the URIs from.
     */
    public UriResource(URI baseUri) {
        setBaseUri(baseUri);
    }

    /**
     * @return the baseUri
     */
    @XmlTransient
    public URI getBaseUri() {
        return baseUri;
    }

    /**
     * @param baseUri
     *            the baseUri to set
     */
    public void setBaseUri(URI baseUri) {
        this.baseUri = baseUri;
    }

    /**
     * @return URI of the resource.
     */
    public URI getUri() {
        // Override this method.
        return null;
    }
}
