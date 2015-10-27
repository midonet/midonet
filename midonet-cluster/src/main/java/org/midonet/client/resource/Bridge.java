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

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoDhcpSubnet;
import org.midonet.client.dto.DtoDhcpSubnet6;
import org.midonet.client.dto.DtoMacPort;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class Bridge extends ResourceBase<Bridge, DtoBridge> {

    public Bridge(WebResource resource, URI uriForCreation, DtoBridge b) {
        super(resource, uriForCreation, b,
              MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
    }

    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    public String getTenantId() {
        return principalDto.getTenantId();
    }

    public Bridge name(String name) {
        principalDto.setName(name);
        return this;
    }

    public Bridge adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    public Bridge tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }

    public Bridge inboundFilterId(UUID id) {
        principalDto.setInboundFilterId(id);
        return this;
    }

    public Bridge outboundFilterId(UUID id) {
        principalDto.setOutboundFilterId(id);
        return this;
    }

    public ResourceCollection<BridgePort> getPorts() {
        return getChildResources(
                principalDto.getPorts(),
                null,
                MidonetMediaTypes.APPLICATION_PORT_V3_COLLECTION_JSON(),
                BridgePort.class, DtoBridgePort.class);
    }

    public ResourceCollection<Port<?,?>> getPeerPorts() {
        ResourceCollection<Port<?,?>> peerPorts =
                new ResourceCollection<>(new ArrayList<Port<?,?>>());

        DtoPort[] dtoPeerPorts = resource.get(
                principalDto.getPeerPorts(),
                null,
                DtoPort[].class,
                MidonetMediaTypes.APPLICATION_PORT_V3_COLLECTION_JSON());

        for (DtoPort pp : dtoPeerPorts) {
            if (pp instanceof DtoRouterPort) {
                RouterPort rp = new RouterPort(
                        resource,
                        principalDto.getPorts(),
                        (DtoRouterPort) pp);
                peerPorts.add(rp);

            } else if (pp instanceof DtoBridgePort) {
                // Interior bridge ports can be linked if one of the 2 has a
                // vlan id assigned, this should be validated upon creation
                BridgePort bp = new BridgePort(
                        resource,
                        principalDto.getPorts(),
                        (DtoBridgePort) pp);
                peerPorts.add(bp);
            }
        }
        return peerPorts;
    }

    public BridgePort addPort() {
        return new BridgePort(
                resource,
                principalDto.getPorts(),
                new DtoBridgePort());
    }

    public DhcpSubnet addDhcpSubnet() {
        return new DhcpSubnet(resource, principalDto.getDhcpSubnets(),
                          new DtoDhcpSubnet());
    }

    public ResourceCollection<DhcpSubnet> getDhcpSubnets() {
        return getChildResources(
            principalDto.getDhcpSubnets(),
            null,
            MidonetMediaTypes.APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2(),
            DhcpSubnet.class, DtoDhcpSubnet.class);
    }


    public DhcpSubnet6 addDhcpSubnet6() {
        return new DhcpSubnet6(resource, principalDto.getDhcpSubnet6s(),
                          new DtoDhcpSubnet6());
    }

    public ResourceCollection<DhcpSubnet6> getDhcpSubnet6s() {
        return getChildResources(
            principalDto.getDhcpSubnet6s(),
            null,
            MidonetMediaTypes.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON(),
            DhcpSubnet6.class, DtoDhcpSubnet6.class);
    }

    private URI getMacTableUri(Short vlanId) {
        return (vlanId == null) ?
                principalDto.getMacTable() :
                createUriFromTemplate(principalDto.getVlanMacTableTemplate(),
                        "{vlanId}", vlanId);
    }

    public ResourceCollection<MacPort> getMacTable(Short vlanId) {
        return getChildResources(getMacTableUri(vlanId), null,
                MidonetMediaTypes.APPLICATION_MAC_PORT_COLLECTION_JSON_V2(),
                MacPort.class, DtoMacPort.class);
    }

    public MacPort addMacPort(Short vlanId, String macAddr, UUID portId) {
        DtoMacPort mp = new DtoMacPort(macAddr, portId);
        return new MacPort(resource, getMacTableUri(vlanId), mp);
    }

    public MacPort getMacPort(Short vlanId, String macAddr, UUID portId) {
        String uriMacAddr = macAddr.replace(':', '-');
        URI uri = (vlanId == null) ?
                UriBuilder.fromPath(principalDto.getMacPortTemplate())
                        .build(uriMacAddr, portId) :
                UriBuilder.fromPath(principalDto.getVlanMacPortTemplate())
                        .build(vlanId, uriMacAddr, portId);
        DtoMacPort mp = resource.get(uri, null, DtoMacPort.class,
                            MidonetMediaTypes.APPLICATION_MAC_PORT_JSON_V2());
        return new MacPort(resource, mp.getUri(), mp);
    }

    @Override
    public String toString() {
        return String.format("Bridge{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
