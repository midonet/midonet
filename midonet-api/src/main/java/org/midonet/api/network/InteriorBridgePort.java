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

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port;

/**
 * DTO for interior bridge port.
 */
public class InteriorBridgePort extends BridgePort implements InteriorPort {

    /**
     * Default constructor
     */
    public InteriorBridgePort() {
        super();
    }

    public InteriorBridgePort(
            org.midonet.cluster.data.ports.BridgePort
                    portData) {
        super(portData);
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_BRIDGE;
    }

    @Override
    public org.midonet.cluster.data.ports.BridgePort toData() {
        org.midonet.cluster.data.ports.BridgePort data =
                new org.midonet.cluster.data.ports.BridgePort();
        super.setConfig(data);
        data.setProperty(Port.Property.v1PortType,
                Client.PortType.InteriorBridge.toString());

        return data;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId + ", vlanId = " + vlanId;
    }
}
