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

import java.net.InetAddress;
import java.util.ArrayList;

import org.midonet.cluster.rest_api.models.Host;

public class HostDataConverter {

    public static Host fromData(org.midonet.cluster.data.host.Host host) {
        Host h = new Host();

        h.id = host.getId();
        h.name = host.getName();
        h.floodingProxyWeight = host.getFloodingProxyWeight();

        h.addresses = new ArrayList<>();
        if (host.getAddresses() != null) {
            for (InetAddress inetAddress : host.getAddresses()) {
                h.addresses.add(inetAddress.toString());
            }
        }

        h.alive = host.getIsAlive();
        h.hostInterfaces = new ArrayList<>();
        if (host.getInterfaces() != null) {
            for (org.midonet.cluster.data.host.Interface intf :
                host.getInterfaces()) {
                h.hostInterfaces.add(
                    InterfaceDataConverter.fromData(intf, host.getId())
                );
            }
        }

        return h;
    }

}
