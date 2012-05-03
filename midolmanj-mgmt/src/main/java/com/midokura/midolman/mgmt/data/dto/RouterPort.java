/*
 * @(#)RouterPort        1.6 12/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import com.midokura.midolman.state.PortConfig;

/**
 * Data transfer class for router port.
 *
 * @version 1.6 12 Sept 2011
 * @author Ryu Ishimoto
 */
public abstract class RouterPort extends Port {

    /**
     * Network IP address
     */
    protected String networkAddress = null;

    /**
     * Network IP address length
     */
    protected int networkLength;

    /**
     * Port IP address
     */
    protected String portAddress = null;

    /**
     * Constructor
     */
    public RouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param vifId
     *            ID of the VIF
     * @param config
     *            PortConfig object
     */
    public RouterPort(UUID id, UUID vifId, PortConfig config) {
        super(id, vifId, config);
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", networkAddress=" + networkAddress
                + ", networkLength=" + networkLength + ", portAddress="
                + portAddress;
    }

}
