/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.util.Net;
import com.midokura.util.StringUtil;

/**
 * Data transfer class for router port.
 */
public abstract class RouterPort extends Port {

    /**
     * Network IP address
     */
    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    protected String networkAddress;

    /**
     * Network IP address length
     */
    @Min(0)
    @Max(32)
    protected int networkLength;

    /**
     * Port IP address
     */
    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    protected String portAddress;

    /**
     * Port MAC address
     */
    protected String portMac;

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
     * @param config
     * @param mgmtConfig
     */
    public RouterPort(UUID id, RouterPortConfig config) {
        super(id, config);
        this.networkAddress = config.getNwAddr();
        this.networkLength = config.nwLength;
        this.portAddress = config.getPortAddr();
        this.portMac = config.getHwAddr().toString();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            ID of the device
     */
    public RouterPort(UUID id, UUID deviceId) {
        super(id, deviceId);
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

    public String getPortMac() {
        return portMac;
    }

    public void setPortMac(String portMac) {
        this.portMac = portMac;
    }

    /**
     * Set the PortConfig fields
     *
     * @param config
     *            RouterPortConfig object
     */
    public void setConfig(RouterPortConfig config) {
        super.setConfig(config);
        config.nwAddr = Net.convertStringAddressToInt(this.networkAddress);
        config.nwLength = this.networkLength;
        config.portAddr = Net.convertStringAddressToInt(this.portAddress);
        if (this.portMac != null)
            config.setHwAddr(MAC.fromString(this.portMac));
    }

    @Override
    public boolean isRouterPort() {
        return true;
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
                + portAddress + ", portMac=" + portMac;
    }
}
