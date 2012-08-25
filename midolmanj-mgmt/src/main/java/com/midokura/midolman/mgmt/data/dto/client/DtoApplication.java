/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoApplication {
    private String version;
    private URI uri;
    private URI hosts;
    private URI bridges;
    private URI chains;
    private URI portGroups;
    private URI routers;

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

    public URI getBridges() {
        return bridges;
    }

    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    public URI getChains() {
        return chains;
    }

    public void setChains(URI chains) {
        this.chains = chains;
    }

    public URI getPortGroups() {
        return portGroups;
    }

    public void setPortGroups(URI portGroups) {
        this.portGroups = portGroups;
    }

    public URI getRouters() {
        return routers;
    }

    public void setRouters(URI routers) {
        this.routers = routers;
    }

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
    }
}
