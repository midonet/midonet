/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * DTO for interior bridge port.
 */
public class InteriorBridgePort extends BridgePort implements InteriorPort {

    /**
     * Default constructor
     */
    public InteriorBridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public InteriorBridgePort(
            org.midonet.cluster.data.ports.LogicalBridgePort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
        this.vlanId = portData.getVlanId();
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_BRIDGE;
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.LogicalBridgePort data =
                new org.midonet.cluster.data.ports.LogicalBridgePort()
                        .setPeerId(this.peerId)
                        .setVlanId(this.vlanId);
        super.setConfig(data);
        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId + ", vlanId = " + vlanId;
    }
}
