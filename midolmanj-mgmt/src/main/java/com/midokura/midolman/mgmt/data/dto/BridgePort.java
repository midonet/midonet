/*
 * @(#)BridgePort        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Class representing a bridge port.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class BridgePort extends Port {

    /**
     * Default constructor
     */
    public BridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param mgmtConfig
     *            PortMgmtConfig object
     * @param config
     *            PortConfig object
     */
    public BridgePort(UUID id, PortMgmtConfig mgmtConfig, PortConfig config) {
        this(id, config.device_id, mgmtConfig.vifId);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of port
     * @param deviceId
     *            Device ID
     * @param vifId
     *            VIF ID
     */
    public BridgePort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId, vifId);
    }

    /**
     * Convert this object to PortConfig object.
     *
     * @return PortConfig object.
     */
    @Override
    public PortConfig toConfig() {
        return new PortDirectory.BridgePortConfig(deviceId);
    }

    /**
     * Convert this object to PortMgmtConfig object.
     *
     * @return PortMgmtConfig object.
     */
    @Override
    public PortMgmtConfig toMgmtConfig() {
        return new PortMgmtConfig(vifId);
    }

    /**
     * Convert this object to ZkNodeEntry object.
     *
     * @return ZkNodeEntry object.
     */
    @Override
    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(id, toConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public PortType getType() {
        return PortType.BRIDGE;
    }
}
