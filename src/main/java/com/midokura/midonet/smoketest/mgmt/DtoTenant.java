/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.mgmt;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoTenant {

    private String id;
    private URI bridges;
    private URI routers;
    private URI uri;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public URI getBridges() {
        return bridges;
    }

    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    public URI getRouters() {
        return routers;
    }

    public void setRouters(URI routers) {
        this.routers = routers;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

}
