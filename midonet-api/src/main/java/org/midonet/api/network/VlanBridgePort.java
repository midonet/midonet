/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;

import org.midonet.api.ResourceUriBuilder;

/**
 * Class representing a vlan-aware bridge port.
 */
public abstract class VlanBridgePort extends Port {

    /**
     * Default constructor
     */
    public VlanBridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id ID of the port
     */
    public VlanBridgePort(UUID id, UUID deviceId) {
        super(id, deviceId);
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public VlanBridgePort(
        org.midonet.cluster.data.ports.VlanBridgePort portData) {
        super(portData);
    }

    /**
     * @param portData VlanBridgePort data object to set.
     */
    public void setConfig(
            org.midonet.cluster.data.ports.VlanBridgePort portData) {
        super.setConfig(portData);
    }

    /**
     * @return the vlan bridge URI
     */
    @Override
    public URI getDevice() {
        if (getBaseUri() != null && deviceId != null) {
            return ResourceUriBuilder.getVlanBridge(getBaseUri(), deviceId);
        } else {
            return null;
        }
    }

    @Override
    public boolean isVlanBridgePort() {
        return true;
    }

    /**
     * Dummy method, vlan ports are deprecated.
     */
    @Override
    public boolean isLinkable(Port port) {
        return false;
    }
}
