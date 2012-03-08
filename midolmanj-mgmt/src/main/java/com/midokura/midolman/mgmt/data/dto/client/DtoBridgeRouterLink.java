/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoBridgeRouterLink {
    private UUID routerPortId;
    private UUID bridgePortId;
    private UUID routerId;
    private UUID bridgeId;
    private URI uri;

    public UUID getRouterPortId() {
        return routerPortId;
    }

    public void setRouterPortId(UUID routerPortId) {
        this.routerPortId = routerPortId;
    }

    public UUID getBridgePortId() {
        return bridgePortId;
    }

    public void setBridgePortId(UUID bridgePortId) {
        this.bridgePortId = bridgePortId;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    public UUID getBridgeId() {
        return bridgeId;
    }

    public void setBridgeId(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoBridgeRouterLink that = (DtoBridgeRouterLink) o;

        if (bridgeId != null ? !bridgeId.equals(that.bridgeId) : that.bridgeId != null)
            return false;
        if (bridgePortId != null ? !bridgePortId.equals(that.bridgePortId) : that.bridgePortId != null)
            return false;
        if (routerId != null ? !routerId.equals(that.routerId) : that.routerId != null)
            return false;
        if (routerPortId != null ? !routerPortId.equals(that.routerPortId) : that.routerPortId != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = routerPortId != null ? routerPortId.hashCode() : 0;
        result = 31 * result + (bridgePortId != null ? bridgePortId.hashCode() : 0);
        result = 31 * result + (routerId != null ? routerId.hashCode() : 0);
        result = 31 * result + (bridgeId != null ? bridgeId.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }
}
