/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoRouter {
    private UUID id;
    private String name;
    private String tenantId;
    private UUID inboundFilter;
    private UUID outboundFilter;
    private URI uri;
    private URI peerPorts;
    private URI ports;
    private URI chains;
    private URI routes;

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

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
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

    public URI getChains() {
        return chains;
    }

    public void setChains(URI chains) {
        this.chains = chains;
    }

    public URI getRoutes() {
        return routes;
    }

    public void setRoutes(URI routes) {
        this.routes = routes;
    }

    public URI getPeerPorts() {
        return peerPorts;
    }

    public void setPeerPorts(URI peerPorts) {
        this.peerPorts = peerPorts;
    }
}
