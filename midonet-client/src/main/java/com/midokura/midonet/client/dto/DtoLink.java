/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoLink {

    private UUID portId;
    private UUID peerId;
    private URI port;
    private URI peer;
    private URI uri;

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public URI getPort() {
        return port;
    }

    public void setPort(URI port) {
        this.port = port;
    }

    public URI getPeer() {
        return peer;
    }

    public void setPeer(URI peer) {
        this.peer = peer;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }
}
