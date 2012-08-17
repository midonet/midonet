/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;

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
     * @param id
     * @param config
     */
    public LogicalBridgePort(UUID id, LogicalBridgePortConfig config) {
        super(id, config);
        this.peerId = config.peerId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.LogicalPort#getPeerId()
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
     * @see com.midokura.midolman.mgmt.data.dto.LogicalPort#getLink()
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
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.LOGICAL_BRIDGE;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#isLogical()
     */
    @Override
    public boolean isLogical() {
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#attachmentId()
     */
    @Override
    public UUID getAttachmentId() {
        return this.peerId;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dto.Port#setAttachmentId(java.util.UUID)
     */
    @Override
    public void setAttachmentId(UUID id) {
        this.peerId = id;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.BridgePort#toConfig()
     */
    @Override
    public PortConfig toConfig() {
        PortDirectory.LogicalBridgePortConfig config = new PortDirectory.LogicalBridgePortConfig();
        super.setConfig(config);
        config.setPeerId(this.peerId);
        return config;
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
