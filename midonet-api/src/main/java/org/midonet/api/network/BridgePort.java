/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;

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
            org.midonet.cluster.data.ports.BridgePort portData) {
        super(portData);
    }

    /**
     * @param portData
     *            BridgePort data object to set.
     */
    public void setConfig(
            org.midonet.cluster.data.ports.BridgePort portData) {
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

    @Override
    public boolean isBridgePort() {
        return true;
    }

    @Override
    public boolean isVlanBridgePort() {
        return false;
    }

}
