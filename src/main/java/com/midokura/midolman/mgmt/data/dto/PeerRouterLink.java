/*
 * @(#)PeerRouterLink        1.6 11/09/19
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;

/**
 * Class representing port.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class PeerRouterLink extends UriResource {

    private UUID portId = null;
    private UUID peerPortId = null;
    private UUID routerId = null;
    private UUID peerRouterId = null;

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
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getRouterLink(getBaseUri(), routerId, peerRouterId);
    }

    public PeerRouterConfig toConfig() {
        return new PeerRouterConfig(portId, peerPortId);
    }

    public static PeerRouterLink createPeerRouterLink(PeerRouterConfig config) {
        PeerRouterLink link = new PeerRouterLink();
        link.setPortId(config.portId);
        link.setPeerPortId(config.peerPortId);
        return link;
    }
}
