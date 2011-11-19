/*
 * @(#)Admin        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.UriManager;

/**
 * Admin DTO.
 * 
 * @version 1.6 20 Nov 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Admin extends UriResource {

    /**
     * Constructor
     * 
     * @param baseUri
     *            The base URI to construct all the URIs from.
     */
    public Admin(URI baseUri) {
        super(baseUri);
    }

    /**
     * @return the init URI
     */
    public URI getInit() {
        return UriManager.getInit(getBaseUri());
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getAdmin(getBaseUri());
    }
}
