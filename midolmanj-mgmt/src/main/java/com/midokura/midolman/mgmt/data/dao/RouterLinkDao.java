/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterPort;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Router linking.
 */
public interface RouterLinkDao {

    /**
     * Create new logical ports linking two routers.
     *
     * @param port
     *            LogicalRouterPort to create.
     * @return PeerRouterLink object.
     * @throws StateAccessException
     *             Data Access error.
     */
    PeerRouterLink create(LogicalRouterPort port) throws StateAccessException;

    /**
     * Create new logical ports linking a router and bridge.
     *
     * @param port
     *            BridgeRouterPort to create.
     * @return BridgeRouterLink object.
     * @throws StateAccessException
     *             Data Access error.
     */
    BridgeRouterLink create(BridgeRouterPort port) throws StateAccessException;

    /**
     * Delete a Router link.
     *
     * @param routerId
     *            ID of the router on the link to delete.
     * @param peerRouterId
     *            ID of the peer router on the link to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID routerId, UUID peerRouterId) throws StateAccessException;

    /**
     * Delete a Router link.
     *
     * @param routerId
     *            ID of the router on the link to delete.
     * @param bridgeId
     *            ID of the bridge on the link to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void deleteBridgeLink(UUID routerId, UUID bridgeId)
            throws StateAccessException;

    /**
     * Get a PeerRouterLink object.
     *
     * @param routerId
     *            ID of the router on the link to get.
     * @param peerRouterId
     *            ID of the peer router on the link to get.
     * @return PeerRouterLink object.
     * @throws StateAccessException
     *             Data Access error.
     */
    PeerRouterLink get(UUID routerId, UUID peerRouterId)
            throws StateAccessException;

    /**
     * Get a BridgeRouterLink object.
     *
     * @param routerId
     *            ID of the router on the link to get.
     * @param bridgeId
     *            ID of the bridge on the link to get.
     * @return BridgeRouterLink object.
     * @throws StateAccessException
     *             Data Access error.
     */
    BridgeRouterLink getBridgeLink(UUID routerId, UUID bridgeId)
            throws StateAccessException;

    /**
     * List router links.
     *
     * @param routerId
     *            ID of the router to get the list of links for.
     * @return A list of router links.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<PeerRouterLink> list(UUID routerId) throws StateAccessException;

    /**
     * List bridge links.
     *
     * @param routerId
     *            ID of the router to get the list of links for.
     * @return A list of links to bridges.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<BridgeRouterLink> listBridgeLinks(UUID routerId)
            throws StateAccessException;

}
