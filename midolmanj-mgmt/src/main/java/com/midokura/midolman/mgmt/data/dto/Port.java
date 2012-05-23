/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Class representing port.
 */
public abstract class Port extends UriResource {

    /**
     * Port ID
     */
    protected UUID id = null;

    /**
     * Device ID
     */
    protected UUID deviceId = null;

    /**
     * Inbound Filter Chain ID
     */
    protected UUID inboundFilter = null;

    /**
     * Outbound Filter Chain ID
     */
    protected UUID outboundFilter = null;

    /**
     * List of Port Groups to which this port belongs.
     */
    protected UUID[] portGroupIDs = null;

    /**
     * VIF ID
     */
    protected UUID vifId = null;

    /**
     * Default constructor
     */
    public Port() {
    }

    /**
     * Constructor
     *
     * @param id
     *            Port ID
     * @param vifId
     *            VIF ID
     */
    public Port(UUID id, UUID device_id, UUID vifId) {
        this.id = id;
        this.vifId = vifId;
        this.deviceId = device_id;
    }

    /**
     * Get port ID.
     *
     * @return port ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set port ID.
     *
     * @param id
     *            ID of the port.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get device ID.
     *
     * @return device ID.
     */
    public UUID getDeviceId() {
        return deviceId;
    }

    /**
     * @return the device URI
     */
    abstract public URI getDevice();

    /**
     * logical Set device ID.
     *
     * @param deviceId
     *            ID of the device.
     */
    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
    }

    public UUID[] getPortGroupIDs() {
        return portGroupIDs;
    }

    public void setPortGroupIDs(UUID[] portGroupIDs) {
        this.portGroupIDs = portGroupIDs;
    }

    /**
     * @return the vifId
     */
    public UUID getVifId() {
        return vifId;
    }

    /**
     * @param vifId
     *            the vifId to set
     */
    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to PortConfig object.
     *
     * @return PortConfig object.
     */
    public abstract PortConfig toConfig();

    public void toConfig(PortConfig config) {
        config.device_id = deviceId;
        config.inboundFilter = inboundFilter;
        config.outboundFilter = outboundFilter;
        if (this.portGroupIDs != null) {
            config.portGroupIDs = new HashSet<UUID>(
                    Arrays.asList(this.portGroupIDs));
        } else {
            config.portGroupIDs = new HashSet<UUID>();
        }
        ;
    }

    /**
     * Convert this object to PortMgmtConfig object.
     *
     * @return PortMgmtConfig object.
     */
    public PortMgmtConfig toMgmtConfig() {
        return new PortMgmtConfig(this.getVifId());
    }

    /**
     * Convert this object to ZkNodeEntry object.
     *
     * @return ZkNodeEntry object.
     */
    public abstract ZkNodeEntry<UUID, PortConfig> toZkNode();

    /**
     * @return　The port type
     */
    public abstract PortType getType();

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", deviceId=" + deviceId + ", vifId=" + vifId;
    }

}
