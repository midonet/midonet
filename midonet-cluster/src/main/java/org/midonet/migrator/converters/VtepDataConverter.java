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

import java.util.Collections;
import java.util.UUID;

import org.midonet.cluster.rest_api.models.Vtep;

public class VtepDataConverter {
    public static Vtep fromData(org.midonet.cluster.data.VTEP vtepData) {

        Vtep vtep = new Vtep();
        vtep.id = UUID.randomUUID();
        vtep.managementIp = vtepData.getId().toString(); // ID is IPv4Addr
        vtep.managementPort = vtepData.getMgmtPort();
        vtep.tunnelZoneId = vtepData.getTunnelZoneId();

        if (vtepData.getTunnelIp() != null) {
            vtep.tunnelIpAddrs =
                Collections.singletonList(vtepData.getTunnelIp().toString());
        } else {
            vtep.tunnelIpAddrs = Collections.emptyList();
        }

        return vtep;
    }
}
