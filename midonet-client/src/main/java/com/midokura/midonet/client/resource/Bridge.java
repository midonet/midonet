/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoDhcpSubnet;
import com.midokura.midonet.client.dto.DtoLogicalBridgePort;
import com.midokura.midonet.client.dto.DtoLogicalRouterPort;
import com.midokura.midonet.client.dto.DtoPort;

public class Bridge extends ResourceBase<Bridge, DtoBridge> {

    public Bridge(WebResource resource, URI uriForCreation, DtoBridge b) {
        super(resource, uriForCreation, b,
              VendorMediaType.APPLICATION_BRIDGE_JSON);
    }

    /**
     * Gets URI of this resource
     *
     * @return URI of this resource
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets ID of this resource
     *
     * @return UUID
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets inbound filter ID
     *
     * @return UUID of the inbound filter
     */
    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    /**
     * Gets name of the bridge
     *
     * @return name
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Gets ID of the outbound filter id
     *
     * @return UUID of the outbound filter
     */
    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }


    /**
     * Gets ID string of the tenant owning this bridge
     *
     * @return tenant ID string
     */
    public String getTenantId() {
        return principalDto.getTenantId();
    }

    /**
     * Sets name to the DTO.
     *
     * @param name
     * @return this
     */
    public Bridge name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets tenantID
     *
     * @param tenantId
     * @return this
     */
    public Bridge tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }


    /**
     * Sets inbound filter id to the DTO
     *
     * @param id
     * @return this
     */
    public Bridge inboundFilterId(UUID id) {
        principalDto.setInboundFilterId(id);
        return this;
    }

    /**
     * Sets outbound filter id to the DTO
     *
     * @param id
     * @return this
     */
    public Bridge outboundFilterId(UUID id) {
        principalDto.setOutboundFilterId(id);
        return this;
    }

    /**
     * Returns collection of ports  under the bridge (downtown is
     * where I drew some blood).
     *
     * @return collection of ports
     */

    public ResourceCollection<BridgePort> getPorts() {
        return getChildResources(
            principalDto.getPorts(),
            null,
            VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
            BridgePort.class, DtoBridgePort.class);
    }

    /**
     * Returns collection of ports that are connected to this bridge
     *
     * @return collection of ports
     */
    public ResourceCollection<Port> getPeerPorts() {
        ResourceCollection<Port> peerPorts =
            new ResourceCollection<Port>(new ArrayList<Port>());

        DtoPort[] dtoPeerPorts = resource.get(
            principalDto.getPeerPorts(),
            null,
            DtoPort[].class,
            VendorMediaType.APPLICATION_PORT_COLLECTION_JSON);

        for (DtoPort pp : dtoPeerPorts) {
            System.out.println("pp in the bridge resource: " + pp);
            if (pp instanceof DtoLogicalRouterPort) {
                RouterPort rp = new RouterPort<DtoLogicalRouterPort>(
                    resource,
                    principalDto.getPorts(),
                    (DtoLogicalRouterPort) pp);
                peerPorts.add(rp);

            } else if (pp instanceof DtoLogicalBridgePort) {
                throw new IllegalStateException(
                    "MidoNet doesn't support linking bridge to brdige.");
            }
        }
        return peerPorts;
    }

    /**
     * Returns bridge port resource object.
     *
     * @return bridge port object
     */
    public BridgePort addMaterializedPort() {
        return new BridgePort<DtoBridgePort>(resource, principalDto.getPorts(),
                                             new DtoBridgePort());
    }

    /**
     * Returns bridge port resource object.
     *
     * @return bridge port object for
     */
    public BridgePort addLogicalPort() {
        return new BridgePort<DtoLogicalBridgePort>(resource,
                                                    principalDto.getPorts(),
                                                    new DtoLogicalBridgePort());
    }

    public DhcpSubnet addDhcpSubnet() {
        return new DhcpSubnet(resource, principalDto.getDhcpSubnets(),
                          new DtoDhcpSubnet());
    }

    /**
     * Returns subnets that belong to the bridge
     *
     * @return collection of subnets
     */

    public ResourceCollection getDhcpSubnets() {
        return getChildResources(
            principalDto.getDhcpSubnets(),
            null,
            VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
            DhcpSubnet.class, DtoDhcpSubnet.class);
    }

    @Override
    public String toString() {
        return String.format("Bridge{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
