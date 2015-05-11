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
package org.midonet.cluster.rest_api.conversion;

import java.net.URI;
import java.util.UUID;

import org.midonet.cluster.rest_api.models.Interface;
import org.midonet.cluster.rest_api.models.Interface.Endpoint;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.packets.MAC;

import static org.midonet.cluster.rest_api.models.Interface.*;
import static org.midonet.midolman.host.state.HostDirectory.Interface.Type.valueOf;

public class InterfaceDataConverter {

    public org.midonet.cluster.data.host.Interface toData(Interface i) {
        byte[] mac = null;
        if (i.mac != null) {
            mac = MAC.fromString(i.mac).getAddress();
        }

        HostDirectory.Interface.Type type = null;
        if (i.type != null) {
            type = valueOf(i.type.name());
        }

        return new org.midonet.cluster.data.host.Interface()
                .setName(i.name)
                .setMac(mac)
                .setStatus(i.status)
                .setMtu(i.mtu)
                .setType(type)
                .setAddresses(i.addresses)
                .setEndpoint(i.endpoint.toString());
    }

    public static org.midonet.cluster.rest_api.models.Interface fromData(
        org.midonet.cluster.data.host.Interface ifData, URI baseUri)
    throws IllegalAccessException {
        org.midonet.cluster.rest_api.models.Interface i =
            new org.midonet.cluster.rest_api.models.Interface();
        i.name = ifData.getName();
        if (ifData.getMac() != null) {
            i.mac = MAC.fromAddress(ifData.getMac()).toString();
        }
        i.status = ifData.getStatus();
        i.mtu = ifData.getMtu();
        i.hostId = UUID.fromString(ifData.getId());
        if (ifData.getType() != null) {
            i.type = InterfaceType.valueOf(ifData.getType().name());
        }
        i.addresses = ifData.getAddresses();
        i.endpoint = Endpoint.valueOf(ifData.getEndpoint());
        i.setBaseUri(baseUri);
        return i;
    }

}
