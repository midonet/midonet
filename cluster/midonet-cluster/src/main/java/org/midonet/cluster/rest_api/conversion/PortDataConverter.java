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

import java.net.URI;
import java.util.UUID;

import org.midonet.cluster.Client;
import org.midonet.cluster.rest_api.models.BridgePort;
import org.midonet.cluster.rest_api.models.ExteriorBridgePort;
import org.midonet.cluster.rest_api.models.ExteriorRouterPort;
import org.midonet.cluster.rest_api.models.InteriorBridgePort;
import org.midonet.cluster.rest_api.models.InteriorRouterPort;
import org.midonet.cluster.rest_api.models.Port;
import org.midonet.cluster.rest_api.models.RouterPort;
import org.midonet.cluster.rest_api.models.VxLanPort;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.cluster.Client.PortType.ExteriorBridge;
import static org.midonet.cluster.Client.PortType.ExteriorRouter;
import static org.midonet.cluster.Client.PortType.InteriorBridge;
import static org.midonet.cluster.Client.PortType.InteriorRouter;
import static org.midonet.cluster.data.Port.Property.v1PortType;
import static org.midonet.cluster.data.Port.Property.vif_id;

public class PortDataConverter {

    public static org.midonet.cluster.data.Port<?, ?> toData(Port dto) {
        org.midonet.cluster.data.Port<?, ?> data;
        if (dto instanceof InteriorBridgePort) {                     // v1
            data = new org.midonet.cluster.data.ports.BridgePort();
            data.setProperty(v1PortType, ExteriorBridge.toString());
        }  else if (dto instanceof ExteriorBridgePort) {             // v1
            data = new org.midonet.cluster.data.ports.BridgePort();
            data.setProperty(v1PortType, InteriorBridge.toString());
        } else if (dto instanceof BridgePort) {                      // v2
            data = new org.midonet.cluster.data.ports.BridgePort();
            if(data.isExterior() || (data.isUnplugged()
                   && Client.PortType.ExteriorBridge.toString()
                       .equals(data.getProperty(
                           org.midonet.cluster.data.Port.Property.v1PortType)))) {
                data.setProperty(v1PortType, ExteriorBridge.toString());
            } else {
                data.setProperty(v1PortType, InteriorBridge.toString());
            }
        } else if (dto instanceof InteriorRouterPort) {              // v1
            data = new org.midonet.cluster.data.ports.RouterPort();
            data.setProperty(v1PortType, InteriorRouter.toString()); // v1
        } else if (dto instanceof ExteriorRouterPort) {
            RouterPort typedDto = (RouterPort)dto;
            org.midonet.cluster.data.ports.RouterPort
                typedData = new org.midonet.cluster.data.ports.RouterPort();
            typedData.setProperty(v1PortType, ExteriorRouter.toString());
            typedData.setNwAddr(typedDto.networkAddress);
            typedData.setNwLength(typedDto.networkLength);
            typedData.setPortAddr(typedDto.portAddress);
            if (typedDto.portMac != null) {
                typedData.setHwAddr(MAC.fromString(typedDto.portMac));
            }
            data = typedData;
        } else if (dto instanceof RouterPort) {                      // v2
            data = new org.midonet.cluster.data.ports.RouterPort();
            if (data.isExterior() || (data.isUnplugged()
                    && Client.PortType.ExteriorRouter.toString()
                        .equals(data.getProperty(
                            org.midonet.cluster.data.Port.Property.v1PortType)))) {
                data.setProperty(v1PortType, ExteriorRouter.toString());
            } else {
                data.setProperty(v1PortType, InteriorRouter.toString());
            }
        } else if (dto instanceof VxLanPort) {
            org.midonet.cluster.data.ports.VxLanPort typedData =
                new org.midonet.cluster.data.ports.VxLanPort();
            VxLanPort typedDto = (VxLanPort)dto;
            typedData.setMgmtIpAddr(IPv4Addr.fromString(typedDto.mgmtIpAddr));
            typedData.setMgmtPort(typedDto.mgmtPort);
            typedData.setVni(typedDto.vni);
            data = typedData;
        } else {
            throw new IllegalArgumentException("Unknown port type: " + dto);
        }

        if (data instanceof org.midonet.cluster.data.ports.RouterPort) {
            org.midonet.cluster.data.ports.RouterPort typedData =
                (org.midonet.cluster.data.ports.RouterPort)data;
            RouterPort typedDto = (RouterPort)dto;
            typedData.setNwAddr(typedDto.networkAddress);
            typedData.setNwLength(typedDto.networkLength);
            typedData.setPortAddr(typedDto.portAddress);
            if (typedDto.portMac != null) {
                typedData.setHwAddr(MAC.fromString(typedDto.portMac));
            }
        }

        data.setId(dto.id);
        data.setAdminStateUp(dto.adminStateUp);
        data.setDeviceId(dto.getDeviceId());
        data.setInboundFilter(dto.inboundFilterId);
        data.setOutboundFilter(dto.outboundFilterId);
        data.setHostId(dto.getHostId());
        data.setInterfaceName(dto.getInterfaceName());
        data.setPeerId(dto.getPeerId());
        if (dto.vifId != null) {
            data.setProperty(vif_id, dto.vifId.toString());
        }
        return data;
    }

    public static Port toV1Dto(org.midonet.cluster.data.Port data,
                               URI baseUri) {
        return toDto(data, baseUri);
    }

    public static Port toDto(org.midonet.cluster.data.Port<?, ?> data,
                             URI baseUri) {
        Port dto;
        if (data instanceof org.midonet.cluster.data.ports.RouterPort) {
            org.midonet.cluster.data.ports.RouterPort typedData =
                (org.midonet.cluster.data.ports.RouterPort)data;
            RouterPort typedDto = new RouterPort();
            dto = typedDto;
            typedDto.networkAddress = typedData.getNwAddr();
            typedDto.networkLength = typedData.getNwLength();
            typedDto.portAddress = typedData.getPortAddr();
            typedDto.portMac = typedData.getHwAddr().toString();
        } else if (data instanceof org.midonet.cluster.data.ports.BridgePort) {
            org.midonet.cluster.data.ports.BridgePort typedData =
                (org.midonet.cluster.data.ports.BridgePort)data;
            BridgePort typedDto = new BridgePort();
            if (typedData.getVlanId() != null) {
                typedDto.vlanId = typedData.getVlanId();
            }
            dto = typedDto;
        } else if (data instanceof org.midonet.cluster.data.ports.VxLanPort) {
            org.midonet.cluster.data.ports.VxLanPort typedData =
                (org.midonet.cluster.data.ports.VxLanPort)data;
            VxLanPort typedDto = new VxLanPort();
            typedDto.mgmtIpAddr = typedData.getMgmtIpAddr().toString();
            typedDto.mgmtPort = typedData.getMgmtPort();
            typedDto.vni = typedData.getVni();
            dto = typedDto;
        } else {
            throw new IllegalArgumentException(
                "Port type not recognized: " + data);
        }
        dto.id = data.getId();
        dto.setDeviceId(data.getDeviceId());
        dto.adminStateUp = data.isAdminStateUp();
        dto.inboundFilterId = data.getInboundFilter();
        dto.outboundFilterId = data.getOutboundFilter();
        dto.setHostId(data.getHostId());
        dto.setInterfaceName(data.getInterfaceName());
        dto.setPeerId(data.getPeerId());
        if (data.getProperty(vif_id) != null) {
            dto.vifId = UUID.fromString(data.getProperty(vif_id));
        }
        dto.setBaseUri(baseUri);
        return dto;
    }

}
