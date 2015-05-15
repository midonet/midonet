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

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.rest_api.models.Host;
import org.midonet.cluster.rest_api.models.Interface;
import org.midonet.packets.MAC;

public class HostDataConverter {

    public static Host fromData(org.midonet.cluster.data.host.Host host,
                                URI baseUri) throws IllegalAccessException {
        Host h = new Host();

        h.setId(host.getId());
        h.setName(host.getName());
        h.setFloodingProxyWeight(host.getFloodingProxyWeight());

        List<String> addresses = new ArrayList<>();
        if (host.getAddresses() != null) {
            for (InetAddress inetAddress : host.getAddresses()) {
                addresses.add(inetAddress.toString());
            }
        }
        h.setAddresses(addresses);

        h.setAlive(host.getIsAlive());
        List<Interface> hostInterfaces = new ArrayList<>();
        if (host.getInterfaces() != null) {
            for (org.midonet.cluster.data.host.Interface intf :
                host.getInterfaces()) {
                hostInterfaces.add(
                    InterfaceDataConverter.fromData(intf, host.getId(), baseUri)
                );
            }
        }
        h.setHostInterfaces(hostInterfaces);

        h.setBaseUri(baseUri);

        return h;
    }

}
