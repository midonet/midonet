/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.Port.Property;

import java.net.URI;
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
            org.midonet.cluster.data.ports.MaterializedBridgePort
                    portData) {
        super(portData);
        if (portData.getProperty(Property.vif_id) != null) {
            this.vifId = UUID.fromString(portData.getProperty(Property.vif_id));
        }
        this.hostId = portData.getHostId();
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
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.MaterializedBridgePort data =
                new org.midonet.cluster.data.ports
                        .MaterializedBridgePort();
        if (this.vifId != null) {
            data.setProperty(Property.vif_id, this.vifId.toString());
        }
        super.setConfig(data);
        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }
}
