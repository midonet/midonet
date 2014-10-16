/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;

import com.google.common.base.Objects;
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
    public org.midonet.cluster.data.ports.BridgePort toData() {
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
        if (Objects.equal(id, otherPort.getId())) {
            return false;
        }

        // If both are bridge ports allowed as long as only one has VLAN ID
        if (otherPort instanceof BridgePort) {
            Short myVlanId = getVlanId();
            Short herVlanId = otherPort.getVlanId();
            if ((myVlanId == null && herVlanId == null) ||
                    (myVlanId != null && herVlanId != null)) {
                return false;
            }
        }

        return true;
    }
}
