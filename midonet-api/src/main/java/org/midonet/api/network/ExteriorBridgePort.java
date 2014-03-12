/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port.Property;
import java.util.UUID;
/**
 * DTO for exterior bridge port
 */
public class ExteriorBridgePort extends BridgePort implements ExteriorPort {

    /**
     * Default constructor
     */
    public ExteriorBridgePort() {
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
    public ExteriorBridgePort(UUID id, UUID deviceId) {
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
    public ExteriorBridgePort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param portData
     *            Exterior bridge port data object
     */
    public ExteriorBridgePort(
            org.midonet.cluster.data.ports.BridgePort
                    portData) {
        super(portData);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.midonet.api.network.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.EXTERIOR_BRIDGE;
    }

    @Override
    public org.midonet.cluster.data.ports.BridgePort toData() {
        org.midonet.cluster.data.ports.BridgePort data =
                new org.midonet.cluster.data.ports
                        .BridgePort();
        super.setConfig(data);
        data.setProperty(Property.v1PortType,
                Client.PortType.ExteriorBridge.toString());
        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }
}
