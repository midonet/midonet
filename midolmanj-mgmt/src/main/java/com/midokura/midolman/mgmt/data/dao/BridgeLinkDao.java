/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.state.StateAccessException;

public interface BridgeLinkDao {

    /**
     * Get a BridgeRouterLink object.
     *
     * @param bridgeId
     *            ID of the bridge on the link to get.
     * @param routerId
     *            ID of the router on the link to get.
     * @return BridgeRouterLink object.
     * @throws StateAccessException
     *             Data Access error.
     */
    BridgeRouterLink getRouterLink(UUID bridgeId, UUID routerId)
            throws StateAccessException;

    /**
     * List bridge links.
     *
     * @param bridgeId
     *            ID of the bridge to get the list of links for.
     * @return A list of links to routers.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<BridgeRouterLink> listRouterLinks(UUID bridgeId)
            throws StateAccessException;

}
