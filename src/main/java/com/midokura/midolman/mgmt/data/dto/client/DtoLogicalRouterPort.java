/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoLogicalRouterPort extends DtoRouterPort {
    private String peerPortAddress = null;
    private UUID peerRouterId = null;
    private UUID peerId = null;

    private URI uri;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public String getPeerPortAddress() {
        return peerPortAddress;
    }

    public void setPeerPortAddress(String peerPortAddress) {
        this.peerPortAddress = peerPortAddress;
    }

    public UUID getPeerRouterId() {
        return peerRouterId;
    }

    public void setPeerRouterId(UUID peerRouterId) {
        this.peerRouterId = peerRouterId;
    }

    public UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

}
