/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port;
import javax.xml.bind.annotation.XmlRootElement;

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
            org.midonet.cluster.data.ports.RouterPort
                    portData) {
        super(portData);
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        org.midonet.cluster.data.ports.RouterPort data =
                new org.midonet.cluster.data.ports.RouterPort();
        super.setConfig(data);
        data.setProperty(Port.Property.v1PortType,
                Client.PortType.InteriorRouter.toString());
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
