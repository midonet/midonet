/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.Client;
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
     * @param portData
     *            Exterior bridge port data object
     */
    public ExteriorRouterPort(
            org.midonet.cluster.data.ports.RouterPort
                    portData) {
        super(portData);
    }

    @Override
    public org.midonet.cluster.data.ports.RouterPort toData() {
        org.midonet.cluster.data.ports.RouterPort data =
                new org.midonet.cluster.data.ports
                        .RouterPort();
        super.setConfig(data);
        data.setProperty(Port.Property.v1PortType,
                Client.PortType.ExteriorRouter.toString());
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
