/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoRouter {
    private UUID id;
    private String name;
    private boolean adminStateUp = true;
    private String tenantId;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private URI inboundFilter;
    private URI outboundFilter;
    private URI uri;
    private URI peerPorts;
    private URI ports;
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

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public URI getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(URI inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public URI getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(URI outboundFilter) {
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

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoRouter otherRouter = (DtoRouter) other;
        if (!Objects.equal(this.id, otherRouter.getId())) {
            return false;
        }

        if (!Objects.equal(this.name, otherRouter.getName())) {
            return false;
        }

        if (!Objects.equal(this.tenantId, otherRouter.getTenantId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilterId, otherRouter.getInboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilter, otherRouter.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilterId, otherRouter.getOutboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilter, otherRouter.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherRouter.getUri())) {
            return false;
        }

        if (!Objects.equal(this.ports, otherRouter.getPorts())) {
            return false;
        }

        if (!Objects.equal(this.peerPorts, otherRouter.getPeerPorts())) {
            return false;
        }

        if (!Objects.equal(this.routes, otherRouter.getRoutes())) {
            return false;
        }

        if (adminStateUp != otherRouter.adminStateUp) {
            return false;
        }

        return true;
    }
}
