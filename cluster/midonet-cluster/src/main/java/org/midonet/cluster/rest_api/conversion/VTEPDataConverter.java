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

package org.midonet.cluster.rest_api.conversion;

import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.rest_api.models.VTEP;
import org.midonet.midolman.state.VtepConnectionState;
import org.midonet.packets.IPv4Addr;

import static scala.collection.JavaConversions.seqAsJavaList;

public class VTEPDataConverter {

    public static VTEP fromData(org.midonet.cluster.data.VTEP vtepData,
                                PhysicalSwitch ps) {
        VTEP vtep = new VTEP();
        vtep.managementIp = vtepData.getId().toString();
        vtep.managementPort = vtepData.getMgmtPort();
        vtep.tunnelZoneId = vtepData.getTunnelZoneId();

        if (null == ps) {
            vtep.connectionState = VtepConnectionState.ERROR;
        } else {
            vtep.connectionState = VtepConnectionState.CONNECTED;
            vtep.name = ps.name();
            vtep.description = ps.description();
            vtep.tunnelIpAddrs = seqAsJavaList(ps.tunnelIpStrings().toList());
        }

        return vtep;
    }

    public static org.midonet.cluster.data.VTEP toData(VTEP vtep) {
        return new org.midonet.cluster.data.VTEP()
                .setId(IPv4Addr.fromString(vtep.managementIp))
                .setMgmtPort(vtep.managementPort)
                .setTunnelZone(vtep.tunnelZoneId);
    }

}
