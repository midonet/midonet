/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;

import org.midonet.api.ResourceUriBuilder;

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
     * @param portData bridge port data object
     */
    public BridgePort(
        org.midonet.cluster.data.ports.BridgePort
            portData) {
        super(portData);
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
        org.midonet.cluster.data.ports.BridgePort data =
            new org.midonet.cluster.data.ports.BridgePort();
        this.setConfig(data);
        return data;
    }


    public void setConfig(org.midonet.cluster.data.ports.BridgePort data) {
        super.setConfig(data);
        data.setVlanId(this.vlanId);
    }

    @Override
    public boolean isLinkable(Port otherPort) {

        if (otherPort == null) {
            throw new IllegalArgumentException("port cannot be null");
        }

        // Must be two unplugged ports
        if (!isUnplugged() || !otherPort.isUnplugged()) {
            return false;
        }

        // IDs must be set
        if (id == null || otherPort.getId() == null) {
            return false;
        }

        // IDs must not be the same
        if (id == otherPort.getId()) {
            return false;
        }

        // If both are bridge ports allowed as long as only one has VLAN ID
        if (otherPort instanceof BridgePort) {
            Short myVlanId = getVlanId();
            Short herVlanId = ((BridgePort) otherPort).getVlanId();
            if ((myVlanId == null && herVlanId == null) ||
                    (myVlanId != null && herVlanId != null)) {
                return false;
            }
        }

        return true;
    }
}
