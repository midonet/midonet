/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;

/**
 * Data transfer class for logical router port.
 */
@XmlRootElement
public class LogicalRouterPort extends RouterPort implements LogicalPort {

    /**
     * Peer port ID
     */
    protected UUID peerId;

    /**
     * Constructor
     */
    public LogicalRouterPort() {
        super();
    }

    /**
     * Constructor
     * 
     * @param id
     * @param config
     * @param mgmtConfig
     */
    public LogicalRouterPort(UUID id, LogicalRouterPortConfig config,
            PortMgmtConfig mgmtConfig) {
        super(id, config, mgmtConfig);
        this.peerId = config.peerId();
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
     * @see com.midokura.midolman.mgmt.data.dto.LogicalPort#getUnlink()
     */
    @Override
    public URI getUnlink() {
        if (id != null) {
            return ResourceUriBuilder.getPortUnlink(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.midokura.midolman.mgmt.data.dto.Port#toConfig()
     */
    @Override
    public PortConfig toConfig() {
        PortDirectory.LogicalRouterPortConfig config = new PortDirectory.LogicalRouterPortConfig();
        super.setConfig(config);
        config.setPeerId(this.peerId);
        return config;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.LOGICAL_ROUTER;
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
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
