/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

public class VlanBridge extends ResourceBase<VlanBridge, DtoVlanBridge> {

    public VlanBridge(WebResource resource, URI uriForCreation, DtoVlanBridge b) {
        super(resource, uriForCreation, b,
              VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON);
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
     * Gets name of the vlan bridge
     *
     * @return name
     */
    public String getName() {
        return principalDto.getName();
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
    public VlanBridge name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets tenantID
     *
     * @param tenantId
     * @return this
     */
    public VlanBridge tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }

    /**
     * Returns collection of trunk ports under the vlan bridge.
     *
     * @return collection of trunk ports
     */

    public ResourceCollection<VlanBridgeTrunkPort> getTrunkPorts() {
        return getChildResources(
            principalDto.getTrunkPorts(),
            null,
            VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
            VlanBridgeTrunkPort.class, DtoVlanBridgeTrunkPort.class);
    }

    /**
     * Returns collection of interior ports under the vlan bridge.
     *
     * @return collection of interior ports
     */

    public ResourceCollection<VlanBridgeInteriorPort> getInteriorPorts() {
        return getChildResources(
            principalDto.getInteriorPorts(),
            null,
            VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
            VlanBridgeInteriorPort.class, DtoVlanBridgeInteriorPort.class);
    }

    /**
     * Returns collection of ports that are connected to this vlan bridge
     *
     * @return collection of ports
     */
    public ResourceCollection<Port> getPeerPorts() {
        ResourceCollection<Port> peerPorts =
            new ResourceCollection<Port>(new ArrayList<Port>());

        DtoPort[] dtoPeerPorts = resource.get(
            principalDto.getInteriorPorts(),
            null,
            DtoPort[].class,
            VendorMediaType.APPLICATION_PORT_COLLECTION_JSON);

        for (DtoPort pp : dtoPeerPorts) {
            if (pp instanceof DtoVlanBridgeInteriorPort) {
                BridgePort rp = new BridgePort<DtoInteriorBridgePort>(
                    resource,
                    principalDto.getInteriorPorts(),
                    (DtoInteriorBridgePort) pp);
                peerPorts.add(rp);

            } else {
                throw new IllegalStateException(
                    "MidoNet only supports linking vlan bridges to bridges.");
            }
        }
        return peerPorts;
    }

    /**
     * Returns vlan bridge port resource object.
     *
     * @return vlan bridge port object for
     */
    public VlanBridgeInteriorPort addInteriorPort() {
        return new VlanBridgeInteriorPort(resource,
                                          principalDto.getInteriorPorts(),
                new DtoVlanBridgeInteriorPort());
    }

    /**
     * Adds a new trunk port to the vlan-bridge.
     *
     * @return
     */
    public VlanBridgeTrunkPort addTrunkPort() {
        return new VlanBridgeTrunkPort(resource,
                                       principalDto.getTrunkPorts(),
            new DtoVlanBridgeTrunkPort());
    }


    @Override
    public String toString() {
        return String.format("VlanBridge{id=%s, name=%s}", principalDto.getId(),
                             principalDto.getName());
    }
}
