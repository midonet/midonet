/*
 * @(#)Port        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Class representing port.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
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
     * @param deviceId
     *            Router or Bridge ID
     * @param vifId
     *            VIF ID
     */
    public Port(UUID id, UUID deviceId, UUID vifId) {
        this.id = id;
        this.deviceId = deviceId;
        this.vifId = vifId;
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
     * logical Set device ID.
     *
     * @param id
     *            ID of the device.
     */
    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
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
        return ResourceUriBuilder.getPort(getBaseUri(), id);
    }

    /**
     * Convert this object to PortConfig object.
     *
     * @return PortConfig object.
     */
    public abstract PortConfig toConfig();

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
