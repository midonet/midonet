/*
 * @(#)MaterializedRouterPort        1.6 18/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;


/**
 * Data transfer class for materialized router port.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class MaterializedRouterPort extends RouterPort {

    private String localNetworkAddress = null;
    private int localNetworkLength;

    public MaterializedRouterPort() {
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
}