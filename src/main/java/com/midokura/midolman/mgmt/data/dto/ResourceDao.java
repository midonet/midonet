/*
 * @(#)ResourceDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.bind.annotation.XmlTransient;

public class ResourceDao {
    private URI uri = null;

    /**
     * @return the uri
     */
    public URI getUri() {
        return uri;
    }

    /**
     * @param uri
     *            the uri to set
     */
    public void setUri(URI uri) {
        this.uri = uri;
    }

    /**
     * @param uri
     *            the uri to set
     * @throws URISyntaxException
     */
    @XmlTransient
    public void setUri(String uri) throws URISyntaxException {
        this.uri = new URI(uri);
    }
}
