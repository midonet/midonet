/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.mgmt;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoPeerRouterLink {
    private UUID portId;
    private UUID peerPortId;
    private UUID routerId;
    private UUID peerRouterId;
    private URI uri;

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public UUID getPeerPortId() {
        return peerPortId;
    }

    public void setPeerPortId(UUID peerPortId) {
        this.peerPortId = peerPortId;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    public UUID getPeerRouterId() {
        return peerRouterId;
    }

    public void setPeerRouterId(UUID peerRouterId) {
        this.peerRouterId = peerRouterId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

}
