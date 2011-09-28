/*
 * @(#)Port        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dao.PortZkManagerProxy.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Class representing port.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Port {

    private UUID id = null;
    private UUID deviceId = null;
    private UUID vifId = null;

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

    public PortConfig toConfig() {
        return new PortConfig.BridgePortConfig(this.getDeviceId());
    }

    public PortMgmtConfig toMgmtConfig() {
        return new PortMgmtConfig(this.getVifId());
    }

    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(this.id, toConfig());
    }

    public static Port createPort(UUID id, PortMgmtConfig mgmtConfig,
            PortConfig config) {
        Port port = new Port();
        port.setVifId(mgmtConfig.vifId);
        port.setDeviceId(config.device_id);
        port.setId(id);
        return port;
    }
}
