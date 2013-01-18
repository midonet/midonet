/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.cluster.data.Port;

import java.net.URI;
import java.util.UUID;

/**
 * Data transfer class for exterior router port.
 */
public class ExteriorRouterPort extends RouterPort implements ExteriorPort {

    /**
     * VIF ID
     */
    private UUID vifId;

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
            com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
                    portData) {
        super(portData);
        if (portData.getProperty(Port.Property.vif_id) != null) {
            this.vifId = UUID.fromString(
                    portData.getProperty(Port.Property.vif_id));
        }
    }

    /**
     * @return the vifId
     */
    @Override
    public UUID getVifId() {
        return vifId;
    }

    /**
     * @param vifId
     *            the vifId to set
     */
    @Override
    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

    /**
     * @return the bgps URI
     */
    public URI getBgps() {
        if (getBaseUri() != null && this.getId() != null) {
            return ResourceUriBuilder.getPortBgps(getBaseUri(), this.getId());
        } else {
            return null;
        }
    }

    /**
     * @return the vpns URI
     */
    public URI getVpns() {
        if (getBaseUri() != null && this.getId() != null) {
            return ResourceUriBuilder.getPortVpns(getBaseUri(), this.getId());
        } else {
            return null;
        }
    }

    @Override
    public com.midokura.midonet.cluster.data.Port toData() {
        com.midokura.midonet.cluster.data.ports.MaterializedRouterPort data =
                new com.midokura.midonet.cluster.data.ports
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
    public boolean isInterior() {
        return false;
    }

    @Override
    public UUID getAttachmentId() {
        return this.vifId;
    }

    @Override
    public void setAttachmentId(UUID id) {
        this.vifId = id;
    }

    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }

}
