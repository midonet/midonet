/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

@XmlRootElement
public class BridgeRouterLink extends UriResource {

    private UUID routerPortId = null;
    private UUID bridgePortId = null;
    private UUID routerId = null;
    private UUID bridgeId = null;

    /**
     * Constructor
     */
    public BridgeRouterLink() {
        super();
    }

    /**
     * Constructor
     *
     * @param routerPortId
     *            ID of the router port
     * @param bridgePortId
     *            ID of the bridge port
     * @param routerId
     *            ID of the router
     * @param bridgeId
     *            ID of the bridge.
     */
    public BridgeRouterLink(UUID routerPortId, UUID bridgePortId, UUID routerId,
                            UUID bridgeId) {
        this.routerPortId = routerPortId;
        this.bridgePortId = bridgePortId;
        this.routerId = routerId;
        this.bridgeId = bridgeId;
    }

    /**
     * @return the routerPortId
     */
    public UUID getRouterPortId() {
        return routerPortId;
    }

    /**
     * @param routerPortId
     *            the routerPortId to set
     */
    public void setRouterPortId(UUID routerPortId) {
        this.routerPortId = routerPortId;
    }

    /**
     * @return the bridgePortId
     */
    public UUID getBridgePortId() {
        return bridgePortId;
    }

    /**
     * @param bridgePortId
     *            the bridgePortId to set
     */
    public void setBridgePortId(UUID bridgePortId) {
        this.bridgePortId = bridgePortId;
    }

    /**
     * @return the routerId
     */
    public UUID getRouterId() {
        return routerId;
    }

    /**
     * @param routerId
     *            the routerId to set
     */
    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * @return the bridgeId
     */
    public UUID getBridgeId() {
        return bridgeId;
    }

    /**
     * @param bridgeId
     *            the bridgeId to set
     */
    public void setBridgeId(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getRouterBridge(
                getBaseUri(), routerId, bridgeId);
    }

    @Override
    public String toString() {
        return "routerPortId=" + routerPortId + ", bridgePortId=" + bridgePortId
                + ", routerId=" + routerId + ", bridgeId=" + bridgeId;
    }
}
