/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.midokura.midonet.cluster.data.Port.Property;

import java.util.UUID;
/**
 * DTO for exterior bridge port
 */
public class ExteriorBridgePort extends BridgePort implements ExteriorPort {

    /**
     * VIF ID
     */
    private UUID vifId;

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
            com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
                    portData) {
        super(portData);
        if (portData.getProperty(Property.vif_id) != null) {
            this.vifId = UUID.fromString(portData.getProperty(Property.vif_id));
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midonet.api.network.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.EXTERIOR_BRIDGE;
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
    public com.midokura.midonet.cluster.data.Port toData() {
        com.midokura.midonet.cluster.data.ports.MaterializedBridgePort data =
                new com.midokura.midonet.cluster.data.ports
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
