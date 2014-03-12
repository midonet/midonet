/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port;

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

    public InteriorBridgePort(
            org.midonet.cluster.data.ports.BridgePort
                    portData) {
        super(portData);
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_BRIDGE;
    }

    @Override
    public org.midonet.cluster.data.ports.BridgePort toData() {
        org.midonet.cluster.data.ports.BridgePort data =
                new org.midonet.cluster.data.ports.BridgePort();
        super.setConfig(data);
        data.setProperty(Port.Property.v1PortType,
                Client.PortType.InteriorBridge.toString());

        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId + ", vlanId = " + vlanId;
    }
}
