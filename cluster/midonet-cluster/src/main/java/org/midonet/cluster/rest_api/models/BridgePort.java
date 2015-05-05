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

import java.util.UUID;

import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.rest_api.annotation.ParentId;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.util.UUIDUtil;

@Resource(name = ResourceUris.PORTS, parents = { Bridge.class })
public class BridgePort extends Port {

    @ZoomField(name = "vlan_id")
    public short vlanId;

    @XmlTransient
    @ZoomField(name = "network_id", converter = UUIDUtil.Converter.class)
    @ParentId
    public UUID networkId;

    public String getType() {
        return PortType.BRIDGE;
    }

    @Override
    public UUID getDeviceId() {
        return networkId;
    }

    @Override
    public void setDeviceId(UUID deviceId) {
        networkId = deviceId;
    }

}
