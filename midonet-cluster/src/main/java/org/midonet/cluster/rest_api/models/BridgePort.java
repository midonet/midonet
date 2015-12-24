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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.rest_api.ResourceUris;

public class BridgePort extends Port {

    @ZoomField(name = "vlan_id")
    public short vlanId;

    @JsonIgnore
    @ZoomField(name = "network_id")
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

    @Override
    public URI getDevice() {
        return absoluteUri(ResourceUris.BRIDGES(), bridgeId);
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

    @Override
    public String toString() {
        return toStringHelper()
            .add("vlanId", vlanId)
            .add("bridgeId", bridgeId)
            .toString();
    }
}
