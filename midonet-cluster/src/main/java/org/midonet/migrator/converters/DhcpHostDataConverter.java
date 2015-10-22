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

import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.rest_api.models.DhcpHost;
import org.midonet.cluster.rest_api.models.DhcpV6Host;

public class DhcpHostDataConverter {

    public static DhcpHost fromData(org.midonet.cluster.data.dhcp.Host host) {
        DhcpHost h = new DhcpHost();
        h.ipAddr = host.getIp().toString();
        h.macAddr = host.getMAC().toString();
        h.name = host.getName();
        h.setExtraDhcpOpts(host.getExtraDhcpOpts());
        return h;
    }

    /**
     * The base uri is expected to be that of the subnet to which this host
     * belongs.
     */
    public static DhcpV6Host fromData(V6Host host) {
        DhcpV6Host v6Host = new DhcpV6Host();
        v6Host.fixedAddress = host.getFixedAddress().toString();
        v6Host.clientId = host.getClientId();
        v6Host.name = host.getName();
        return v6Host;
    }
}
