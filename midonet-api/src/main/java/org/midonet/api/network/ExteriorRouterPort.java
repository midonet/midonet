/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.Port;

import java.net.URI;
import java.util.UUID;

/**
 * Data transfer class for exterior router port.
 */
public class ExteriorRouterPort extends RouterPort implements ExteriorPort {

    /**
     * Constructor
     */
    public ExteriorRouterPort() {
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
    public ExteriorRouterPort(UUID id, UUID deviceId) {
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
    public ExteriorRouterPort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param portData
     *            Exterior bridge port data object
     */
    public ExteriorRouterPort(
            org.midonet.cluster.data.ports.MaterializedRouterPort
                    portData) {
        super(portData);
        if (portData.getProperty(Port.Property.vif_id) != null) {
            this.vifId = UUID.fromString(
                    portData.getProperty(Port.Property.vif_id));
        }
        this.hostId = portData.getHostId();
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.MaterializedRouterPort data =
                new org.midonet.cluster.data.ports
                        .MaterializedRouterPort();
        if (this.vifId != null) {
            data.setProperty(Port.Property.vif_id, this.vifId.toString());
        }
        super.setConfig(data);
        return data;
    }

    @Override
    public String getType() {
        return PortType.EXTERIOR_ROUTER;
    }

    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }

}
