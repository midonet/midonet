/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoBridgeRouterPort extends DtoRouterPort {
    private UUID bridgeId = null;
    private UUID peerId = null;

    private URI uri;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getBridgeId() {
        return bridgeId;
    }

    public void setBridgeId(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    public UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

}
