/*
 * Copyright (c) 2013. Midokura Europe SARL
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoVlanBridgeInteriorPort;

public class VlanBridgeInteriorPort
    extends Port<VlanBridgeInteriorPort, DtoVlanBridgeInteriorPort> {

    public VlanBridgeInteriorPort(WebResource resource, URI uriForCreation,
                                  DtoVlanBridgeInteriorPort port) {
        super(resource, uriForCreation, port,
              VendorMediaType.APPLICATION_PORT_JSON);
    }

    /**
     * Sets the vlan id associated to the port.
     *
     * @param vlanId the new vlan id
     * @return this
     */
    public VlanBridgeInteriorPort setVlanId(Short vlanId) {
        principalDto.setVlanId(vlanId);
        return this;
    }

    /**
     * Returns the vlan id associated to this port.
     *
     * @return vlan id.
     */
    public Short getVlanId() {
        return principalDto.getVlanId();
    }

    /**
     * Gets the URI of this vlan bridge port
     *
     * @return
     */
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets ID of this vlan bridge port
     *
     * @return UUID of this port
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets ID of the peer port
     *
     * @return uuid of the peer port
     */
    public UUID getPeerId() {
        return principalDto.getPeerId();
    }

    /**
     * Creates a link to the port with given id
     *
     * @param id id of the peer port
     * @return this
     */
    public VlanBridgeInteriorPort link(UUID id) {
        peerId(id);
        resource.post(principalDto.getLink(),
                      principalDto, VendorMediaType.APPLICATION_PORT_LINK_JSON);
        return get(getUri());
    }

    /**
     * Deletes the link to the peer port
     *
     * @return this
     */
    public VlanBridgeInteriorPort unlink() {
        resource.delete((principalDto).getLink());
        return get(getUri());
    }

    /**
     * Sets peer id for linking
     *
     * @param id
     * @return
     */
    private VlanBridgeInteriorPort peerId(UUID id) {
        principalDto.setPeerId(id);
        return this;
    }

    @Override
    public String toString() {
        return String.format("VlanBridgeInteriorPort{id=%s, type=%s}",
                             getId(),
                             principalDto.getType());
    }
}
