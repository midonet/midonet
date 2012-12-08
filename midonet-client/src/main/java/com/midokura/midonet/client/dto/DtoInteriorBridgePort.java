/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

import java.net.URI;
import java.util.UUID;

public class DtoInteriorBridgePort extends DtoBridgePort implements
        DtoInteriorPort {

    private UUID peerId = null;
    private URI peer = null;
    private URI link = null;
    private URI unlink = null;

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
        return PortType.INTERIOR_BRIDGE;
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        DtoInteriorBridgePort port = (DtoInteriorBridgePort) other;

        if (peerId != null ? !peerId.equals(port.peerId) : port.peerId != null) {
            return false;
        }

        if (peer != null ? !peer.equals(port.peer) : port.peer != null) {
            return false;
        }

        if (link != null ? !link.equals(port.link) : port.link != null) {
            return false;
        }

        if (unlink != null ? !unlink.equals(port.unlink) : port.unlink != null) {
            return false;
        }

        return true;
    }
}
