/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midolman.mgmt.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * DTO for logical bridge port.
 */
public class LogicalBridgePort extends BridgePort implements LogicalPort {

    /**
     * Peer port ID
     */
    private UUID peerId;

    /**
     * Default constructor
     */
    public LogicalBridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public LogicalBridgePort(
            com.midokura.midonet.cluster.data.ports.LogicalBridgePort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.LogicalPort#getPeerId()
     */
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

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.LogicalPort#getLink()
     */
    @Override
    public URI getLink() {
        if (id != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.LOGICAL_BRIDGE;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#isLogical()
     */
    @Override
    public boolean isLogical() {
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#attachmentId()
     */
    @Override
    public UUID getAttachmentId() {
        return this.peerId;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.network.Port#setAttachmentId(java.util.UUID)
     */
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
