/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoApplication {
    private String version;
    private URI uri;
    private URI tenants;
    private URI hosts;

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

    public URI getTenants() {
        return tenants;
    }

    public void setTenants(URI tenants) {
        this.tenants = tenants;
    }

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
    }
}
