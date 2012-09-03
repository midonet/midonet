/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midonet.cluster.data.Port;
import com.midokura.util.StringUtil;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;

/**
 * Data transfer class for materialized router port.
 */
public class MaterializedRouterPort extends RouterPort implements
        MaterializedPort {

    /**
     * VIF ID
     */
    private UUID vifId;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN, message =
            "is an invalid IP format")
    private String localNetworkAddress;

    @Min(0)
    @Max(32)
    private int localNetworkLength;

    /**
     * Constructor
     */
    public MaterializedRouterPort() {
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
    public MaterializedRouterPort(UUID id, UUID deviceId) {
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
    public MaterializedRouterPort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId);
        this.vifId = vifId;
    }

    /**
     * Constructor
     *
     * @param portData
     *            Materialized bridge port data object
     */
    public MaterializedRouterPort(
            com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
                    portData) {
        super(portData);
        this.localNetworkAddress = portData.getLocalNwAddr();
        this.localNetworkLength = portData.getLocalNwLength();
        if (portData.getProperty(Port.Property.vif_id) != null) {
            this.vifId = UUID.fromString(
                    portData.getProperty(Port.Property.vif_id));
        }
    }

    /**
     * @return the localNetworkAddress
     */
    public String getLocalNetworkAddress() {
        return localNetworkAddress;
    }

    /**
     * @param localNetworkAddress
     *            the localNetworkAddress to set
     */
    public void setLocalNetworkAddress(String localNetworkAddress) {
        this.localNetworkAddress = localNetworkAddress;
    }

    /**
     * @return the localNetworkLength
     */
    public int getLocalNetworkLength() {
        return localNetworkLength;
    }

    /**
     * @param localNetworkLength
     *            the localNetworkLength to set
     */
    public void setLocalNetworkLength(int localNetworkLength) {
        this.localNetworkLength = localNetworkLength;
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
                        .MaterializedRouterPort()
                .setLocalNwAddr(this.localNetworkAddress)
                .setLocalNwLength(this.localNetworkLength);
        if (this.vifId != null) {
            data.setProperty(Port.Property.vif_id, this.vifId.toString());
        }
        super.setConfig(data);
        return data;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public String getType() {
        return PortType.MATERIALIZED_ROUTER;
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", localNetworkAddress="
                + localNetworkAddress + ", localNetworkLength="
                + localNetworkLength + ", vifId=" + vifId;
    }

}
