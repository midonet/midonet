/*
 * Copyright 2016 Midokura SARL
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

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPAddr;

@ZoomClass(clazz = Topology.IPAddrGroup.IPAddrPorts.class)
public class IpAddrPort extends ZoomObject {

    @ZoomField(name = "ip_address",
               converter = IPAddressUtil.Converter.class)
    public IPAddr ipAddress;

    @ZoomField(name = "port_ids")
    public List<UUID> portIds;

    public IpAddrPort() { }

    public IpAddrPort(IPAddr ipAddress) {
        this.ipAddress = ipAddress;
    }

}
