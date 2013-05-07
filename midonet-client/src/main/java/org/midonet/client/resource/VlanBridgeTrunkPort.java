/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoVlanBridgeTrunkPort;

public class VlanBridgeTrunkPort extends Port<VlanBridgeTrunkPort, DtoVlanBridgeTrunkPort> {

    public VlanBridgeTrunkPort(WebResource resource, URI uriForCreation,
                               DtoVlanBridgeTrunkPort port) {
        super(resource, uriForCreation, port,
              VendorMediaType.APPLICATION_PORT_JSON);
    }

    /**
     * Gets URI of this vlan bridge port
     *
     * @return URI of the vlan bridge port
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets vif id bound to this vlan bridge port
     *
     * @return UUID of the vif
     */
    public UUID getVifId() {
        return principalDto.getVifId();
    }

    /**
     * Gets vlan bridge device id of this port
     *
     * @return device id
     */
    public UUID getDeviceId() {
        return principalDto.getDeviceId();
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
     * Gets type of this vlan bridge port
     *
     * @return type
     */
    public String getType() {
        return principalDto.getType();
    }

    /**
     * Sets id to the vif id.
     *
     * @param id
     * @return
     */
    public VlanBridgeTrunkPort vifId(UUID id) {
        principalDto.setVifId(id);
        return this;
    }

    @Override
    public String toString() {
        return String.format("VlanBridgeTrunkPort{id=%s, type=%s}",
                             getId(), principalDto.getType());
    }

}
