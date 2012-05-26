/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.PortType;

public class DtoLogicalBridgePort extends DtoBridgePort implements
        DtoLogicalPort {

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
    public URI getUnlink() {
        return this.unlink;
    }

    @Override
    public void setUnlink(URI unlink) {
        this.unlink = unlink;
    }

    @Override
    public String getType() {
        return PortType.LOGICAL_BRIDGE;
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        DtoLogicalBridgePort port = (DtoLogicalBridgePort) other;

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
