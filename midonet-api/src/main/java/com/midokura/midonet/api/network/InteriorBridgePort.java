/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * DTO for interior bridge port.
 */
public class InteriorBridgePort extends BridgePort implements InteriorPort {

    /**
     * Peer port ID
     */
    private UUID peerId;

    /**
     * Default constructor
     */
    public InteriorBridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public InteriorBridgePort(
            com.midokura.midonet.cluster.data.ports.LogicalBridgePort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
    }

    @Override
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId
     *            Peer port ID
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
    public String getType() {
        return PortType.INTERIOR_BRIDGE;
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
    public com.midokura.midonet.cluster.data.Port toData() {
        com.midokura.midonet.cluster.data.ports.LogicalBridgePort data =
                new com.midokura.midonet.cluster.data.ports.LogicalBridgePort()
                        .setPeerId(this.peerId);
        super.setConfig(data);
        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
