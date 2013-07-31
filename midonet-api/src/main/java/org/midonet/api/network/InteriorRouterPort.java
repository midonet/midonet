/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Data transfer class for interior router port.
 */
@XmlRootElement
public class InteriorRouterPort extends RouterPort implements InteriorPort {

    /**
     * Constructor
     */
    public InteriorRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public InteriorRouterPort(
            org.midonet.cluster.data.ports.LogicalRouterPort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.LogicalRouterPort data =
                new org.midonet.cluster.data.ports.LogicalRouterPort()
                        .setPeerId(this.peerId);
        super.setConfig(data);
        return data;
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_ROUTER;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
