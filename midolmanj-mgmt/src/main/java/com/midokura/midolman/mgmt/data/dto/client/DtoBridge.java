/*
 * Copyright 2011 Midokura Japan
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoBridge {
    private UUID id;
    private String name;
    private String tenantId;
    private URI uri;
    private URI ports;
    private URI routers;
    private URI filteringDb;
    private URI dhcpSubnets;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    public URI getRouters() {
        return routers;
    }

    public void setRouters(URI routers) {
        this.routers = routers;
    }

    public URI getFilteringDb() {
        return filteringDb;
    }

    public void setFilteringDb(URI filteringDb) {
        this.filteringDb = filteringDb;
    }

    public URI getDhcpSubnets() {
        return dhcpSubnets;
    }

    public void setDhcpSubnets(URI dhcpSubnets) {
        this.dhcpSubnets = dhcpSubnets;
    }
}
