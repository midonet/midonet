/*
 * @(#)Bgp        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.state.BgpZkManager.BgpConfig;

/**
 * Class representing BGP.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@XmlRootElement
public class Bgp {

    private UUID id = null;
    private int localAS;
    private String peerAddr = null;
    private int peerAS;
    private UUID portId = null;

    /**
     * Get BGP ID.
     * 
     * @return BGP ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set BGP ID.
     * 
     * @param id
     *            ID of the BGP.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get BGP localAS.
     * 
     * @return BGP localAS.
     */
    public int getLocalAS() {
        return localAS;
    }

    /**
     * Set BGP localAS.
     * 
     * @param localAS
     *            localAS of the BGP.
     */
    public void setLocalAS(int localAS) {
        this.localAS = localAS;
    }

    /**
     * Get peer address.
     * 
     * @return peer address.
     */
    public String getPeerAddr() {
        return peerAddr;
    }

    /**
     * Set peer address.
     * 
     * @param peerAddr
     *            Address of the peer.
     */
    public void setPeerAddr(String peerAddr) {
        this.peerAddr = peerAddr;
    }

    /**
     * Get BGP peerAS.
     * 
     * @return BGP peerAS.
     */
    public int getPeerAS() {
        return peerAS;
    }

    /**
     * Set BGP peerAS.
     * 
     * @param peerAS
     *            peerAS of the BGP.
     */
    public void setPeerAS(int peerAS) {
        this.peerAS = peerAS;
    }

    /**
     * Get port ID.
     * 
     * @return Port ID.
     */
    public UUID getPortId() {
        return portId;
    }

    /**
     * Set port ID.
     * 
     * @param portId
     *            Port ID of the BGP.
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public BgpConfig toConfig() throws UnknownHostException {
        return new BgpConfig(this.getPortId(), this.getLocalAS(), InetAddress
                .getByName(this.getPeerAddr()), this.getPeerAS());
    }

    public static Bgp createBgp(UUID id, BgpConfig config) {
        Bgp b = new Bgp();
        b.setLocalAS(config.localAS);
        b.setPeerAddr(config.peerAddr.getHostAddress());
        b.setPeerAS(config.peerAS);
        b.setPortId(config.portId);
        b.setId(id);
        return b;
    }
}
