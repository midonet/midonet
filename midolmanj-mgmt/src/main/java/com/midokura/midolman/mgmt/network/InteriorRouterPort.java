/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midolman.mgmt.ResourceUriBuilder;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Data transfer class for interior router port.
 */
@XmlRootElement
public class InteriorRouterPort extends RouterPort implements InteriorPort {

    /**
     * Peer port ID
     */
    protected UUID peerId;

    /**
     * Constructor
     */
    public InteriorRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public InteriorRouterPort(
            com.midokura.midonet.cluster.data.ports.LogicalRouterPort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
    }

    /**
     * @return the peerId
     */
    @Override
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId
     *            the peerId to set
     */
    @Override
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    /**
     * @return the peer port URI
     */
    @Override
    public URI getPeer() {
        if (peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    @Override
    public URI getLink() {
        if (id != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), id);
        } else {
            return null;
        }
    }

    @Override
    public com.midokura.midonet.cluster.data.Port toData() {
        com.midokura.midonet.cluster.data.ports.LogicalRouterPort data =
                new com.midokura.midonet.cluster.data.ports.LogicalRouterPort()
                        .setPeerId(this.peerId);
        super.setConfig(data);
        return data;
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_ROUTER;
    }

    @Override
    public boolean isInterior() {
        return true;
    }

    @Override
    public UUID getAttachmentId() {
        return this.peerId;
    }

    @Override
    public void setAttachmentId(UUID id) {
        this.peerId = id;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
