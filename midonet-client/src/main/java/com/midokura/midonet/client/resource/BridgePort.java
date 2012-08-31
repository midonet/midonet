/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoLogicalBridgePort;

import java.net.URI;
import java.util.UUID;

public class BridgePort<T extends DtoBridgePort> extends
        Port<BridgePort, T> {

    public BridgePort(WebResource resource, URI uriForCreation, T port) {
        super(resource, uriForCreation, port, VendorMediaType
                .APPLICATION_PORT_JSON);
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
     * Gets array of portgroup Id that this port belongs to
     *
     * @return array of UUID of portgroup
     */
    public UUID[] getPortGroupIDs() {
        return principalDto.getPortGroupIDs();
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
        return ((DtoLogicalBridgePort) principalDto).getPeerId();
    }

    /**
     * 　Sets id to the inbound filter.
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
     * Set list of ids to principalDto group ids
     *
     * @param ids
     * @return this
     */
    public BridgePort portGorupIDs(UUID[] ids) {
        principalDto.setPortGroupIDs(ids);
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
        resource.post(((DtoLogicalBridgePort) principalDto).getLink(),
                principalDto, VendorMediaType.APPLICATION_PORT_JSON);
        return get(getUri());
    }

    /**
     * Deletes the link to the peer port
     *
     * @return this
     */
    public BridgePort unlink() {
        peerId(null);
        resource.post(((DtoLogicalBridgePort) principalDto).getLink(),
                principalDto, VendorMediaType.APPLICATION_PORT_JSON);
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
        ((DtoLogicalBridgePort) principalDto).setPeerId(id);
        return this;
    }

}
