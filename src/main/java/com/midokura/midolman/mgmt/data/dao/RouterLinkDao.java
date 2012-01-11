/*
 * @(#)RouterLinkDao        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Router linking.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
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
     * List router links.
     *
     * @param routerId
     *            ID of the router to get the list of links for.
     * @return A list of router links.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<PeerRouterLink> list(UUID routerId) throws StateAccessException;
}
