/*
 * Copyright 2014 Midokura SARL
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
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBridgePort;

public class BridgePort extends
        Port<BridgePort, DtoBridgePort> {

    public BridgePort(WebResource resource, URI uriForCreation, DtoBridgePort port) {
        super(resource, uriForCreation, port, VendorMediaType
                .APPLICATION_PORT_V2_JSON);
    }

    /**
     * Gets URI of this bridge port
     *
     * @return URI of the bridge port
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets vif id bound to this bridge port
     *
     * @return UUID of the vif
     */
    public UUID getVifId() {
        return principalDto.getVifId();
    }

    /**
     * Gets device(bridge) id of this port
     *
     * @return device id
     */
    public UUID getDeviceId() {
        return principalDto.getDeviceId();
    }

    /**
     * Gets ID of this bridge port
     *
     * @return UUID of this port
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Get administrative state
     *
     * @return administrative state of the port.
     */

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
    }

    /**
     * Gets inbound filter ID of this bridge port
     *
     * @return UUID of the inbound filter
     */
    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    /**
     * Gets outbound filter ID of this bridge port
     *
     * @return UUID of the outbound filter
     */
    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    /**
     * Gets type of this bridge port
     *
     * @return type
     */
    public String getType() {
        return principalDto.getType();
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
     * Gets VLAN ID with which this port is tagged
     *
     * @return Short
     */
    public Short getVlanId() {
        return principalDto.getVlanId();
    }

    /**
     * Set administrative state
     *
     * @param adminStateUp
     *            administrative state of the port.
     */
    public BridgePort adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    /**
     * Sets id to the inbound filter.
     *
     * @param id
     * @return this
     */

    public BridgePort inboundFilterId(UUID id) {
        principalDto.setInboundFilterId(id);
        return this;
    }

    /**
     * Sets id to the outbound filter.
     *
     * @param id
     * @return this
     */
    public BridgePort outboundFilterId(UUID id) {
        principalDto.setOutboundFilterId(id);
        return this;
    }

    /**
     * Sets id to the vif id.
     *
     * @param id
     * @return
     */
    public BridgePort vifId(UUID id) {
        principalDto.setVifId(id);
        return this;
    }

    /**
     * Creates a link to the port with given id
     *
     * @param id id of the peer port
     * @return this
     */
    public BridgePort link(UUID id) {
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
    public BridgePort unlink() {
        resource.delete(principalDto.getLink());
        return get(getUri());
    }

    @Override
    public String toString() {
        return String.format("BridgePort{id=%s, type=%s, inboundFilterId=%s," +
                "outboundFilterId=%s}", principalDto.getId(),
                principalDto.getType(), principalDto.getInboundFilterId(),
                principalDto.getOutboundFilterId());
    }

    /**
     * Sets peer id for linking
     *
     * @param id
     * @return
     */
    private BridgePort peerId(UUID id) {
        principalDto.setPeerId(id);
        return this;
    }

    /**
     * Sets the vlan id (in an interior bridge port)
     *
     * @param vlanId
     * @return
     */
    public BridgePort vlanId(Short vlanId) {
        principalDto.setVlanId(vlanId);
        return this;
    }

}
