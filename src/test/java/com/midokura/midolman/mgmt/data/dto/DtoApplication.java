/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoApplication {
    private String version;
    private URI uri;
    private URI admin;
    private URI tenant;
    private URI vifs;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getTenant() {
        return tenant;
    }

    public void setTenant(URI tenant) {
        this.tenant = tenant;
    }

    public URI getVifs() {
        return vifs;
    }

    public void setVifs(URI vifs) {
        this.vifs = vifs;
    }
}
