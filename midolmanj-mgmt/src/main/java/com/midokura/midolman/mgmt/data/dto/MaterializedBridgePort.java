/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;

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
     * @param mgmtConfig
     *            PortMgmtConfig object
     * @param config
     *            MaterializedRouterPortConfig object
     */
    public MaterializedBridgePort(UUID id, BridgePortConfig config,
            PortMgmtConfig mgmtConfig) {
        super(id, config, mgmtConfig);
        this.vifId = mgmtConfig.vifId;
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
        PortDirectory.BridgePortConfig config = new PortDirectory.BridgePortConfig();
        super.setConfig(config);
        return config;
    }

    /**
     * Convert this object to PortMgmtConfig object.
     *
     * @return PortMgmtConfig object.
     */
    @Override
    public PortMgmtConfig toMgmtConfig() {
        return new PortMgmtConfig(this.getVifId());
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
