/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Data transfer class for router port.
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
     */
    public RouterPort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId, vifId);
    }

    /**
     * @return the router URI
     */
    @Override
    public URI getDevice() {
        if (getBaseUri() != null && deviceId != null) {
            return ResourceUriBuilder.getRouter(getBaseUri(), deviceId);
        } else {
            return null;
        }
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
