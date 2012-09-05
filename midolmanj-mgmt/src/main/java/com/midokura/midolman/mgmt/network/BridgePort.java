/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midolman.mgmt.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Class representing a bridge port.
 */
public abstract class BridgePort extends Port {

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
     */
    public BridgePort(UUID id, UUID deviceId) {
        super(id, deviceId);
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public BridgePort(
            com.midokura.midonet.cluster.data.ports.BridgePort portData) {
        super(portData);
    }

    /**
     * @param portData
     *            BridgePort data object to set.
     */
    public void setConfig(
            com.midokura.midonet.cluster.data.ports.BridgePort portData) {
        super.setConfig(portData);
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

    @Override
    public boolean isRouterPort() {
        return false;
    }
}
