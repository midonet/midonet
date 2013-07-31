/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Class representing a bridge port.
 */
public class BridgePort extends Port {

    protected Short vlanId;

    /**
     * Default constructor
     */
    public BridgePort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of port
     */
    public BridgePort(UUID id, UUID deviceId) {
        super(id, deviceId);
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public BridgePort(
            org.midonet.cluster.data.ports.BridgePort portData) {
        super(portData);
    }

    /**
     * Constructor
     *
     * @param portData
     *            Exterior bridge port data object
     */
    public BridgePort(
            org.midonet.cluster.data.ports.MaterializedBridgePort
                    portData) {
        super(portData);
        if (portData.getProperty(
            org.midonet.cluster.data.Port.Property.vif_id) != null) {
            this.vifId = UUID.fromString(portData.getProperty(
                org.midonet.cluster.data.Port.Property.vif_id));
        }
        this.hostId = portData.getHostId();
    }

    /**
     * Constructor
     *
     * @param portData
     */
    public BridgePort(
            org.midonet.cluster.data.ports.LogicalBridgePort
                    portData) {
        super(portData);
        this.peerId = portData.getPeerId();
        this.vlanId = portData.getVlanId();
    }

    /**
     * @return the bridge URI
     */
    @Override
    public URI getDevice() {
        if (getBaseUri() != null && deviceId != null) {
            return ResourceUriBuilder.getBridge(getBaseUri(), deviceId);
        } else {
            return null;
        }
    }

    @Override
    public boolean isVlanBridgePort() {
        return false;
    }

    public Short getVlanId() {
        return vlanId;
    }

    public void setVlanId(Short vlanId) {
        this.vlanId = vlanId;
    }

    @Override
    public String getType() {
            return PortType.BRIDGE;
    }

    @Override
    public org.midonet.cluster.data.Port toData() {
        if(isExterior()) {
            org.midonet.cluster.data.ports.MaterializedBridgePort data =
                    new org.midonet.cluster.data.ports
                            .MaterializedBridgePort();
            if (this.vifId != null) {
                data.setProperty(org.midonet.cluster.data.Port.Property.vif_id,
                    this.vifId.toString());
            }
            super.setConfig(data);
            return data;
        } else if(isInterior()) {
            org.midonet.cluster.data.ports.LogicalBridgePort data =
                    new org.midonet.cluster.data.ports.LogicalBridgePort()
                            .setPeerId(this.peerId)
                            .setVlanId(this.vlanId);
            super.setConfig(data);
            return data;
        } else
            return null; //av-mido: Unplugged ports are not yet implemented (in
                         // this commit) at the cluster layer.
    }

    @Override
    public boolean isLinkable(Port port) {

        if (port == null) {
            throw new IllegalArgumentException("port cannot be null");
        }

        // Must be two unplugged/interior ports
        if (!isUnplugged() || !port.isUnplugged()) {
            return false;
        }

        // IDs must be set
        if (id == null || port.getId() == null) {
            return false;
        }

        // IDs must not be the same
        if (id == port.getId()) {
            return false;
        }

        // If both are bridge ports allowed as long as only one has VLAN ID
        if (port instanceof BridgePort) {
            Short myVlanId = getVlanId();
            Short herVlanId = ((BridgePort) port).getVlanId();
            if ((myVlanId == null && herVlanId == null) ||
                    (myVlanId != null && herVlanId != null)) {
                return false;
            }
        }

        // Finally, both ports must be unlinked
        return (getPeerId() == null && port.getAttachmentId() == null);
    }


}
