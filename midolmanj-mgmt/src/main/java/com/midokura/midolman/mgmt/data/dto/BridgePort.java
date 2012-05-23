/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Class representing a bridge port.
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
     *            ID of port
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
        PortDirectory.BridgePortConfig config = new PortDirectory.BridgePortConfig();
        super.toConfig(config);
        return config;
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

    /**
     * @return the bridge URI
     */
    @Override
    public URI getDevice() {
        if (getBaseUri() != null && deviceId != null) {
            return ResourceUriBuilder.getBridge(getBaseUri(), deviceId);
        } else {
            return null;
        }
    }
}
