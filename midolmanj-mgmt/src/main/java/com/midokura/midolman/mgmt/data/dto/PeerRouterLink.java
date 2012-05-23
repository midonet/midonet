/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Class representing port.
 */
@XmlRootElement
public class PeerRouterLink extends UriResource {

    private UUID portId = null;
    private UUID peerPortId = null;
    private UUID routerId = null;
    private UUID peerRouterId = null;

    /**
     * Constructor
     */
    public PeerRouterLink() {
        super();
    }

    /**
     * Constructor
     *
     * @param config
     *            PeerRouterConfig object
     * @param routerId
     *            ID of the router
     * @param peerRouterId
     *            ID of the peer router.
     */
    public PeerRouterLink(PeerRouterConfig config, UUID routerId,
            UUID peerRouterId) {
        this(config.portId, config.peerPortId, routerId, peerRouterId);
    }

    /**
     * Constructor
     *
     * @param portId
     *            ID of the port
     * @param peerPortId
     *            ID of the peer port
     * @param routerId
     *            ID of the router
     * @param peerRouterId
     *            ID of the peer router.
     */
    public PeerRouterLink(UUID portId, UUID peerPortId, UUID routerId,
            UUID peerRouterId) {
        this.portId = portId;
        this.peerPortId = peerPortId;
        this.routerId = routerId;
        this.peerRouterId = peerRouterId;
    }

    /**
     * @return the portId
     */
    public UUID getPortId() {
        return portId;
    }

    /**
     * @param portId
     *            the portId to set
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    /**
     * @return the peerPortId
     */
    public UUID getPeerPortId() {
        return peerPortId;
    }

    /**
     * @param peerPortId
     *            the peerPortId to set
     */
    public void setPeerPortId(UUID peerPortId) {
        this.peerPortId = peerPortId;
    }

    /**
     * @return the peer port URI
     */
    public URI getPeerPort() {
        if (getBaseUri() != null && peerPortId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerPortId);
        } else {
            return null;
        }
    }

    /**
     * @return the routerId
     */
    public UUID getRouterId() {
        return routerId;
    }

    /**
     * @param routerId
     *            the routerId to set
     */
    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * @return the router URI
     */
    public URI getRouter() {
        if (getBaseUri() != null && routerId != null) {
            return ResourceUriBuilder.getRouter(getBaseUri(), routerId);
        } else {
            return null;
        }
    }

    /**
     * @return the peerRouterId
     */
    public UUID getPeerRouterId() {
        return peerRouterId;
    }

    /**
     * @param peerRouterId
     *            the peerRouterId to set
     */
    public void setPeerRouterId(UUID peerRouterId) {
        this.peerRouterId = peerRouterId;
    }

    /**
     * @return the peer router URI
     */
    public URI getPeerRouter() {
        if (getBaseUri() != null && peerRouterId != null) {
            return ResourceUriBuilder.getRouter(getBaseUri(), peerRouterId);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && routerId != null && peerRouterId != null) {
            return ResourceUriBuilder.getRouterLink(getBaseUri(), routerId,
                    peerRouterId);
        } else {
            return null;
        }
    }

    /**
     * @return PeerRouterConfig object
     */
    public PeerRouterConfig toConfig() {
        return new PeerRouterConfig(portId, peerPortId);
    }

    /**
     * Convert to LogicalRouterPort object
     *
     * @return LogicalRouterPort object
     */
    public LogicalRouterPort toLogicalRouterPort() {
        LogicalRouterPort port = new LogicalRouterPort(portId, routerId);
        port.setPeerId(peerPortId);
        port.setPeerRouterId(peerRouterId);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "portId=" + portId + ", peerPortId=" + peerPortId
                + ", routerId=" + routerId + ", peerRouterId=" + peerRouterId;
    }
}
