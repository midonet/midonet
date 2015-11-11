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

import java.util.ArrayList;
import java.util.UUID;

import scala.collection.immutable.Map;

import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.rest_api.models.ExteriorBridgePort;
import org.midonet.cluster.rest_api.models.ExteriorRouterPort;
import org.midonet.cluster.rest_api.models.InteriorBridgePort;
import org.midonet.cluster.rest_api.models.InteriorRouterPort;
import org.midonet.cluster.rest_api.models.Port;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;

import static org.midonet.util.StringUtil.toStringOrNull;

public class PortDataConverter {
    public static Port fromData(org.midonet.cluster.data.Port<?, ?> port,
                                Map<IPv4Addr, UUID> vtepIds)
        throws SerializationException, StateAccessException {

        Port dto;
        if (port instanceof BridgePort) {
            BridgePort typedPort = (BridgePort)port;
            org.midonet.cluster.rest_api.models.BridgePort typedDto =
                port.getPeerId() != null ?
                new InteriorBridgePort() : new ExteriorBridgePort();
            typedDto.bridgeId = typedPort.getDeviceId();
            typedDto.vlanId = typedPort.getVlanId() != null ?
                typedPort.getVlanId() : 0;
            dto = typedDto;
        } else if (port instanceof RouterPort) {
            RouterPort typedPort = (RouterPort)port;
            org.midonet.cluster.rest_api.models.RouterPort typedDto =
                port.getPeerId() != null ?
                new InteriorRouterPort() : new ExteriorRouterPort();
            typedDto.networkAddress = typedPort.getNwAddr();
            typedDto.networkLength = typedPort.getNwLength();
            typedDto.portAddress = typedPort.getPortAddr();
            typedDto.portMac = toStringOrNull(typedPort.getHwAddr());
            typedDto.routerId = typedPort.getDeviceId();
            dto = typedDto;
        } else if (port instanceof VxLanPort) {
            VxLanPort typedPort = (VxLanPort)port;
            org.midonet.cluster.rest_api.models.VxLanPort typedDto =
                new org.midonet.cluster.rest_api.models.VxLanPort();
            typedDto.networkId = typedPort.getDeviceId();
            typedDto.vtepId = vtepIds.apply(typedPort.getMgmtIpAddr());
            dto = typedDto;
        } else {
            throw new IllegalArgumentException(
                "Cannot translate port of type " + port.getClass().getName());
        }

        dto.active = port.isActive();
        dto.adminStateUp = port.isAdminStateUp();
        dto.hostId = port.getHostId();
        dto.id = port.getId();
        dto.inboundFilterId = port.getInboundFilter();
        dto.interfaceName = port.getInterfaceName();
        dto.outboundFilterId = port.getOutboundFilter();
        dto.peerId = port.getPeerId();
        dto.portGroupIds = new ArrayList<>(port.getPortGroups());
        dto.tunnelKey = port.getTunnelKey();

        String vifIdStr =
            port.getProperty(org.midonet.cluster.data.Port.Property.vif_id);
        if (vifIdStr != null)
            dto.vifId = UUID.fromString(vifIdStr);

        return dto;
    }
}

