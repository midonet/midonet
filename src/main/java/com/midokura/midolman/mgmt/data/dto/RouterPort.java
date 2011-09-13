/*
 * @(#)RouterPort        1.6 12/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

/**
 * Data access class for router port.
 * 
 * @version 1.6 12 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterPort extends Port {

    public static final String Logical = "Logical";
    public static final String Materialized = "Materialized";

    private String localNetworkAddress = null;
    private int localNetworkLength;
    private UUID peerId = null;
    private String networkAddress = null;
    private int networkLength;
    private String portAddress = null;
    private String type = null;

    public RouterPort() {
        super();
    }

    /**
     * @return the localNetworkAddress
     */
    public String getLocalNetworkAddress() {
        return localNetworkAddress;
    }

    /**
     * @param localNetworkAddress
     *            the localNetworkAddress to set
     */
    public void setLocalNetworkAddress(String localNetworkAddress) {
        this.localNetworkAddress = localNetworkAddress;
    }

    /**
     * @return the localNetworkLength
     */
    public int getLocalNetworkLength() {
        return localNetworkLength;
    }

    /**
     * @param localNetworkLength
     *            the localNetworkLength to set
     */
    public void setLocalNetworkLength(int localNetworkLength) {
        this.localNetworkLength = localNetworkLength;
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
     * @return the networkAddress
     */
    public String getNetworkAddress() {
        return networkAddress;
    }

    /**
     * @param networkAddress
     *            the networkAddress to set
     */
    public void setNetworkAddress(String networkAddress) {
        this.networkAddress = networkAddress;
    }

    /**
     * @return the networkLength
     */
    public int getNetworkLength() {
        return networkLength;
    }

    /**
     * @param networkLength
     *            the networkLength to set
     */
    public void setNetworkLength(int networkLength) {
        this.networkLength = networkLength;
    }

    /**
     * @return the portAddress
     */
    public String getPortAddress() {
        return portAddress;
    }

    /**
     * @param portAddress
     *            the portAddress to set
     */
    public void setPortAddress(String portAddress) {
        this.portAddress = portAddress;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * @param type
     *            the type to set
     */
    public void setType(String type) {
        this.type = type;
    }

}
