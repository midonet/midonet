/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.util.UUIDUtil;

public class BridgePort extends Port {

    @ZoomField(name = "vlan_id")
    public short vlanId;

    @JsonIgnore
    @ZoomField(name = "network_id", converter = UUIDUtil.Converter.class)
    public UUID bridgeId;

    public String getType() {
        return PortType.BRIDGE;
    }

    @Override
    public UUID getDeviceId() {
        return bridgeId;
    }

    @Override
    public void setDeviceId(UUID deviceId) {
        bridgeId = deviceId;
    }

    @JsonIgnore
    public void create(UUID bridgeId) {
        super.create();
        this.bridgeId = bridgeId;
    }

    @JsonIgnore
    @Override
    public void update(Port from) {
        super.update(from);
        BridgePort bridgePort = (BridgePort)from;
        bridgeId = bridgePort.bridgeId;
    }

    public URI getDevice() {
        return absoluteUri(ResourceUris.BRIDGES, bridgeId);
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
        if (id == null || otherPort.id == null) {
            return false;
        }

        // IDs must not be the same
        if (Objects.equal(id, otherPort.id)) {
            return false;
        }

        // If both are bridge ports allowed as long as only one has VLAN ID
        if (otherPort instanceof BridgePort) {
            return vlanId == ((BridgePort) otherPort).vlanId;
        }

        return true;
    }
}
