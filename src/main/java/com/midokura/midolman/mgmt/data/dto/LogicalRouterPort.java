/*
 * @(#)LogicalRouterPort        1.6 18/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

/**
 * Data transfer class for logical router port.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class LogicalRouterPort extends RouterPort {

    private String peerPortAddress = null;
    private UUID peerRouterId = null;
    private UUID peerId = null;

    public LogicalRouterPort() {
        super();
    }

    /**
     * @return the peerId
     */
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId
     *            the peerId to set
     */
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    /**
     * @return the peerPortAddress
     */
    public String getPeerPortAddress() {
        return peerPortAddress;
    }

    /**
     * @param peerPortAddress
     *            the peerPortAddress to set
     */
    public void setPeerPortAddress(String peerPortAddress) {
        this.peerPortAddress = peerPortAddress;
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
}
