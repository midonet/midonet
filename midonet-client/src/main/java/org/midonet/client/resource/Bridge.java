/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.resource;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

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
     * Get administrative state
     *
     * @return administrative state of the bridge.
     */

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
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
     * Set administrative state
     *
     * @param adminStateUp
     *            administrative state of the bridge.
     */
    public Bridge adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
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
                VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON,
                BridgePort.class, DtoBridgePort.class);
    }

    /**
     * Returns collection of ports that are connected to this bridge
     *
     * @return collection of ports
     */
    public ResourceCollection<Port<?,?>> getPeerPorts() {
        ResourceCollection<Port<?,?>> peerPorts =
                new ResourceCollection<>(new ArrayList<Port<?,?>>());

        DtoPort[] dtoPeerPorts = resource.get(
                principalDto.getPeerPorts(),
                null,
                DtoPort[].class,
                VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON);

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

    /**
     * Returns Bridge port resource for creation.
     *
     * @return Bridge port resource
     */
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

    /**
     * Returns subnets that belong to the bridge
     *
     * @return collection of subnets
     */

    public ResourceCollection<DhcpSubnet> getDhcpSubnets() {
        return getChildResources(
            principalDto.getDhcpSubnets(),
            null,
            VendorMediaType.APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
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
            VendorMediaType.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON,
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
                VendorMediaType.APPLICATION_MAC_PORT_COLLECTION_JSON_V2,
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
                            VendorMediaType.APPLICATION_MAC_PORT_JSON_V2);
        return new MacPort(resource, mp.getUri(), mp);
    }

    @Override
    public String toString() {
        return String.format("Bridge{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
