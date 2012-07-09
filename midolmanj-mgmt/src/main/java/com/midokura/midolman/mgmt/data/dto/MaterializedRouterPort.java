/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.util.Net;
import com.midokura.util.StringUtil;

/**
 * Data transfer class for materialized router port.
 */
public class MaterializedRouterPort extends RouterPort implements
        MaterializedPort {

    /**
     * VIF ID
     */
    private UUID vifId;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN, message = "is an invalid IP format")
    private String localNetworkAddress;

    @Min(0)
    @Max(32)
    private int localNetworkLength;

    /**
     * Constructor
     */
    public MaterializedRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            ID of the device
     */
    public MaterializedRouterPort(UUID id, UUID deviceId) {
        super(id, deviceId);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            ID of the device
     * @param vifId
     *            ID of the VIF.
     */
    public MaterializedRouterPort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param config
     *            MaterializedRouterPortConfig object
     * @param config
     *            MaterializedRouterPortConfig object
     */
    public MaterializedRouterPort(UUID id, MaterializedRouterPortConfig config) {
        super(id, config);
        this.localNetworkAddress = Net
                .convertIntAddressToString(config.localNwAddr);
        this.localNetworkLength = config.localNwLength;
        if (config.properties.containsKey(PortProperty.VIF_ID)) {
            this.vifId = UUID.fromString(config.properties
                    .get(PortProperty.VIF_ID));
        }
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
     * @return the vifId
     */
    @Override
    public UUID getVifId() {
        return vifId;
    }

    /**
     * @param vifId
     *            the vifId to set
     */
    @Override
    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

    /**
     * @return the bgps URI
     */
    public URI getBgps() {
        if (getBaseUri() != null && this.getId() != null) {
            return ResourceUriBuilder.getPortBgps(getBaseUri(), this.getId());
        } else {
            return null;
        }
    }

    /**
     * @return the vpns URI
     */
    public URI getVpns() {
        if (getBaseUri() != null && this.getId() != null) {
            return ResourceUriBuilder.getPortVpns(getBaseUri(), this.getId());
        } else {
            return null;
        }
    }

    /**
     * Convert this object to PortConfig object.
     *
     * @return PortConfig object.
     */
    @Override
    public PortConfig toConfig() {
        PortDirectory.MaterializedRouterPortConfig config = new PortDirectory.MaterializedRouterPortConfig();
        super.setConfig(config);
        config.localNwAddr = Net
                .convertStringAddressToInt(this.localNetworkAddress);
        config.localNwLength = this.localNetworkLength;
        if (this.vifId != null) {
            config.properties.put(PortProperty.VIF_ID, this.vifId.toString());
        }
        return config;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.MATERIALIZED_ROUTER;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#isLogical()
     */
    @Override
    public boolean isLogical() {
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#attachmentId()
     */
    @Override
    public UUID getAttachmentId() {
        return this.vifId;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dto.Port#setAttachmentId(java.util.UUID)
     */
    @Override
    public void setAttachmentId(UUID id) {
        this.vifId = id;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", localNetworkAddress="
                + localNetworkAddress + ", localNetworkLength="
                + localNetworkLength + ", vifId=" + vifId;
    }

}
