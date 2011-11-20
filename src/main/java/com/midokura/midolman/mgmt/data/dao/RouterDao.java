/*
 * @(#)RouterDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.StateAccessException;

public interface RouterDao extends OwnerQueryable {

    UUID create(Router router) throws StateAccessException;

    PeerRouterLink createLink(LogicalRouterPort port)
            throws StateAccessException;

    void deleteLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException;

    Router get(UUID id) throws StateAccessException;

    PeerRouterLink getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws StateAccessException;

    List<PeerRouterLink> listPeerRouterLinks(UUID routerId)
            throws StateAccessException;

    List<Router> list(String tenantId) throws StateAccessException;

    void update(Router router) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

}
