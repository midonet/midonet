/*
 * @(#)PeerRouterLink        1.6 11/09/19
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.state.RouterZkManagerProxy.PeerRouterConfig;

/**
 * Class representing port.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class PeerRouterLink {
	
	private UUID portId = null;
    private UUID peerPortId = null;
    private UUID peerRouterId = null;
	
    /**
	 * @return the portId
	 */
	public UUID getPortId() {
		return portId;
	}
	/**
	 * @param portId the portId to set
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
	 * @param peerPortId the peerPortId to set
	 */
	public void setPeerPortId(UUID peerPortId) {
		this.peerPortId = peerPortId;
	}
	/**
	 * @return the peerRouterId
	 */
	public UUID getPeerRouterId() {
		return peerRouterId;
	}
	/**
	 * @param peerRouterId the peerRouterId to set
	 */
	public void setPeerRouterId(UUID peerRouterId) {
		this.peerRouterId = peerRouterId;
	}
	
    public static PeerRouterLink createPeerRouterLink(
            PeerRouterConfig config) {
        PeerRouterLink link = new PeerRouterLink();
        link.setPortId(config.portId);
        link.setPeerPortId(config.peerPortId);
        return link;
    }
}
