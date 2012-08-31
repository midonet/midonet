/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoLogicalRouterPort extends DtoRouterPort implements
        DtoLogicalPort {

    private UUID peerId = null;
    private URI peer = null;
    private URI link = null;

    @Override
    public UUID getPeerId() {
        return peerId;
    }

    @Override
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    @Override
    public URI getPeer() {
        return this.peer;
    }

    @Override
    public void setPeer(URI peer) {
        this.peer = peer;
    }

    @Override
    public URI getLink() {
        return this.link;
    }

    @Override
    public void setLink(URI link) {
        this.link = link;
    }

    @Override
    public String getType() {
        return PortType.LOGICAL_ROUTER;
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        DtoLogicalRouterPort port = (DtoLogicalRouterPort) other;

        if (peerId != null ? !peerId.equals(port.peerId) : port.peerId != null) {
            return false;
        }

        if (peer != null ? !peer.equals(port.peer) : port.peer != null) {
            return false;
        }

        if (link != null ? !link.equals(port.link) : port.link != null) {
            return false;
        }

        return true;
    }
}
