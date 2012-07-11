/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedBridgePortConfig;

/**
 * DTO for materialized bridge port
 */
public class MaterializedBridgePort extends BridgePort implements
        MaterializedPort {

    /**
     * VIF ID
     */
    private UUID vifId;

    /**
     * Default constructor
     */
    public MaterializedBridgePort() {
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
    public MaterializedBridgePort(UUID id, UUID deviceId) {
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
    public MaterializedBridgePort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param config
     *            BrigePortConfig object
     * @param config
     *            MaterializedBridgePortConfig object
     */
    public MaterializedBridgePort(UUID id, MaterializedBridgePortConfig config) {
        super(id, config);
        if (config.properties.containsKey(ConfigProperty.VIF_ID)) {
            this.vifId = UUID.fromString(config.properties
                    .get(ConfigProperty.VIF_ID));
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.MATERIALIZED_BRIDGE;
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
     * @see com.midokura.midolman.mgmt.data.dto.Port#toConfig()
     */
    @Override
    public PortConfig toConfig() {
        PortDirectory.MaterializedBridgePortConfig config = new PortDirectory.MaterializedBridgePortConfig();
        super.setConfig(config);
        if (vifId != null) {
            config.properties.put(ConfigProperty.VIF_ID, vifId.toString());
        }
        return config;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }
}
