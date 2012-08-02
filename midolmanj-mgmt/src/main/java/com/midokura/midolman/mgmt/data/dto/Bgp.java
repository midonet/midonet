/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.zkManagers.BgpZkManager.BgpConfig;

/**
 * Class representing BGP.
 */
@XmlRootElement
public class Bgp extends UriResource {

    private UUID id = null;
    private int localAS;
    private String peerAddr = null;
    private int peerAS;
    private UUID portId = null;

    /**
     * Default constructor
     */
    public Bgp() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of BGP
     * @param config
     *            BgpConfig object.
     */
    public Bgp(UUID id, BgpConfig config) {
        this(id, config.localAS, config.peerAddr.getHostAddress(),
                config.peerAS, config.portId);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of BGP
     * @param localAS
     *            Local AS number
     * @param peerAddr
     *            Peer IP address
     * @param peerAS
     *            Peer AS number
     * @param portId
     *            Port ID
     */
    public Bgp(UUID id, int localAS, String peerAddr, int peerAS, UUID portId) {
        this.id = id;
        this.localAS = localAS;
        this.peerAddr = peerAddr;
        this.peerAS = peerAS;
        this.portId = portId;
    }

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

    /**
     * @return the port URI
     */
    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        } else {
            return null;
        }

    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBgp(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the Ad routes URI
     */
    public URI getAdRoutes() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBgpAdRoutes(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public BgpConfig toConfig() {
        try {
            return new BgpConfig(this.getPortId(), this.getLocalAS(),
                    InetAddress.getByName(this.getPeerAddr()), this.getPeerAS());
        } catch (UnknownHostException e) {
            // This exception should never be thrown.
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", localAS=" + localAS + ", peerAddr=" + peerAddr
                + ", peerAS=" + peerAS + ", portId=" + portId;
    }

}
