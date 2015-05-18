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

import org.midonet.cluster.data.dhcp.Host;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.rest_api.models.DhcpHost;
import org.midonet.cluster.rest_api.models.DhcpV6Host;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;

public class DhcpHostDataConverter {

    public static DhcpHost fromData(org.midonet.cluster.data.dhcp.Host host,
                                    URI baseUri) {
        DhcpHost h = new DhcpHost();
        h.ipAddr = host.getIp().toString();
        h.macAddr = host.getMAC().toString();
        h.name = host.getName();
        h.setExtraDhcpOpts(host.getExtraDhcpOpts());
        h.setBaseUri(baseUri);
        return h;
    }

    public static org.midonet.cluster.data.dhcp.Host toData(DhcpHost h) {
        return new Host()
                .setIp(IPv4Addr.fromString(h.ipAddr))
                .setMAC(MAC.fromString(h.macAddr))
                .setName(h.name)
                .setExtraDhcpOpts(h.getExtraDhcpOpts());
    }

    public static DhcpV6Host fromData(V6Host host, UUID bridgeId,
                                      IPv6Subnet prefix, URI baseUri) {
        DhcpV6Host v6Host = new DhcpV6Host();
        v6Host.fixedAddress = host.getFixedAddress().toString();
        v6Host.clientId = host.getClientId();
        v6Host.name = host.getName();
        v6Host.bridgeId = bridgeId;
        v6Host.prefix = prefix;
        v6Host.setBaseUri(baseUri);
        return v6Host;
    }

    public static V6Host toData(DhcpV6Host h) {
        V6Host v6Host = new V6Host();
        v6Host.setFixedAddress(IPv6Addr.fromString(h.fixedAddress));
        v6Host.setClientId(h.clientId);
        v6Host.setName(h.name);
        return v6Host;
    }



}
