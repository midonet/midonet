/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.packets.MAC;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.Random;
import java.util.UUID;

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
     * @param portData
     */
    public LogicalRouterPort(
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

    @Override
    public com.midokura.midonet.cluster.data.Port toData() {
        com.midokura.midonet.cluster.data.ports.LogicalRouterPort data =
                new com.midokura.midonet.cluster.data.ports.LogicalRouterPort()
                        .setPeerId(this.peerId);
        super.setConfig(data);
        return data;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.LOGICAL_ROUTER;
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
