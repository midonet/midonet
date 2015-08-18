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
package org.midonet.migrator.converters;

import java.util.UUID;

import org.midonet.cluster.rest_api.models.Interface.Endpoint;
import org.midonet.packets.MAC;

import static org.midonet.cluster.rest_api.models.Interface.InterfaceType;

public class InterfaceDataConverter {

    public static org.midonet.cluster.rest_api.models.Interface fromData(
        org.midonet.cluster.data.host.Interface ifData, UUID hostId) {

        org.midonet.cluster.rest_api.models.Interface i =
            new org.midonet.cluster.rest_api.models.Interface();
        i.name = ifData.getName();
        if (ifData.getMac() != null) {
            i.mac = MAC.fromAddress(ifData.getMac()).toString();
        }
        i.status = ifData.getStatus();
        i.mtu = ifData.getMtu();
        i.hostId = hostId;
        if (ifData.getType() != null) {
            i.type = InterfaceType.valueOf(ifData.getType().name());
        }
        i.addresses = ifData.getAddresses();
        if (ifData.getEndpoint() != null) {
            i.endpoint = Endpoint.valueOf(ifData.getEndpoint());
        }
        return i;
    }

}
