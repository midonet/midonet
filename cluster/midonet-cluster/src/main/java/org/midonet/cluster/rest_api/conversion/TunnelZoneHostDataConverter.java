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

import java.util.UUID;

import org.midonet.cluster.data.TunnelZone.HostConfig;
import org.midonet.cluster.rest_api.models.TunnelZoneHost;
import org.midonet.packets.IPv4Addr;

public class TunnelZoneHostDataConverter {

    public static TunnelZoneHost fromData(UUID tunnelZoneId, HostConfig data) {
        TunnelZoneHost tzh = new TunnelZoneHost();
        tzh.tunnelZoneId = tunnelZoneId;
        tzh.hostId = UUID.fromString(data.getId().toString());
        tzh.ipAddress = data.getIp().toString();
        return tzh;
    }

    public static HostConfig toData(TunnelZoneHost tzh) {
        HostConfig data = new HostConfig(null, new HostConfig.Data());
        data.setId(tzh.hostId);
        data.setIp(IPv4Addr.fromString(tzh.ipAddress));
        return data;
    }

}
