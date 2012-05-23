/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.HashSet;
import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.util.Net;

public class BridgeRouterPort extends RouterPort {

    /**
     * Bridge ID
     */
    protected UUID bridgeId = null;

    /**
     * Peer port ID
     */
    protected UUID peerId = null;

    /**
     * Constructor
     */
    public BridgeRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param config
     *            LogicalRouterPortConfig object
     */
    public BridgeRouterPort(UUID id,
            PortDirectory.LogicalRouterPortConfig config) {
        this(id, config.device_id);
        this.networkAddress = Net.convertIntAddressToString(config.nwAddr);
        this.networkLength = config.nwLength;
        this.portAddress = Net.convertIntAddressToString(config.portAddr);
        this.peerId = config.peer_uuid;
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            Router ID
     */
    public BridgeRouterPort(UUID id, UUID deviceId) {
        super(id, deviceId, null);
    }

    /**
     * @return the peerId
     */
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId
     *            the peerId to set
     */
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    /**
     * @return the peer port URI
     */
    public URI getPeer() {
        if (peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    /**
     * @return the connected bridge's Id
     */
    public UUID getBridgeId() {
        return bridgeId;
    }

    /**
     * @param bridgeId
     *            the connected bridge's ID to set
     */
    public void setBridgeId(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * @return the peer bridge port URI
     */
    public URI getBridge() {
        if (getBaseUri() != null && bridgeId != null) {
            return ResourceUriBuilder.getBridge(getBaseUri(), bridgeId);
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#toConfig()
     */
    @Override
    public PortConfig toConfig() {
        PortDirectory.LogicalRouterPortConfig config = new PortDirectory.LogicalRouterPortConfig(
                this.getDeviceId(), Net.convertStringAddressToInt(this
                        .getNetworkAddress()), this.getNetworkLength(),
                Net.convertStringAddressToInt(this.getPortAddress()),
                new HashSet<com.midokura.midolman.layer3.Route>(),
                this.getPeerId(), null);
        super.toConfig(config);
        return config;
    }

    /**
     * @return PortConfig object of the peer.
     */
    public PortConfig toPeerConfig() {
        return new PortDirectory.LogicalBridgePortConfig(this.getBridgeId(),
                this.getId());
    }

    /**
     * @return PeerRouterLink object for the peer.
     */
    public BridgeRouterLink toLink() {
        BridgeRouterLink link = new BridgeRouterLink();
        link.setRouterPortId(this.getId());
        link.setBridgePortId(peerId);
        link.setRouterId(this.getDeviceId());
        link.setBridgeId(this.getBridgeId());
        return link;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#toZkNode()
     */
    @Override
    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(this.getId(), toConfig());
    }

    /**
     * @return ZkNodeEntry object for the peer port node.
     */
    public ZkNodeEntry<UUID, PortConfig> toPeerZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(this.getPeerId(),
                toPeerConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public PortType getType() {
        return PortType.LOGICAL_ROUTER;
    }

    @Override
    public String toString() {
        return "BridgeRouterPort{" + "bridgeId=" + bridgeId + ", peerId="
                + peerId + '}';
    }
}
