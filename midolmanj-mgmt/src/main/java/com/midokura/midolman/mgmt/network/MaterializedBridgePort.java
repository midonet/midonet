/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midonet.cluster.data.Port.Property;

import java.util.UUID;
/**
 * DTO for materialized bridge port
 */
public class MaterializedBridgePort extends BridgePort implements
        MaterializedPort {

    /**
     * VIF ID
     */
    private UUID vifId;

    /**
     * Default constructor
     */
    public MaterializedBridgePort() {
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
    public MaterializedBridgePort(UUID id, UUID deviceId) {
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
    public MaterializedBridgePort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param portData
     *            Materialized bridge port data object
     */
    public MaterializedBridgePort(
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
     * @see com.midokura.midolman.mgmt.network.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.MATERIALIZED_BRIDGE;
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

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#isLogical()
     */
    @Override
    public boolean isLogical() {
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.network.Port#attachmentId()
     */
    @Override
    public UUID getAttachmentId() {
        return this.vifId;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.network.Port#setAttachmentId(java.util.UUID)
     */
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", vifId=" + vifId;
    }
}
