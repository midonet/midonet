/*
 * @(#)RouterDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.state.RouterZkManagerProxy;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Data access class for router.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterDataAccessor extends DataAccessor {
    /*
     * Implements CRUD operations on Router.
     */

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public RouterDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private RouterZkManagerProxy getRouterZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new RouterZkManagerProxy(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    public UUID create(Router router) throws Exception {
        return getRouterZkManager().create(router);
    }

    public PeerRouterLink createLink(LogicalRouterPort port)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, Exception {
        return getRouterZkManager().createLink(port);
    }

    /**
     * Get a Router for the given ID.
     * 
     * @param id
     *            Router ID to search.
     * @return Router object with the given ID.
     * @throws Exception
     *             Error getting data to Zookeeper.
     */
    public Router get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return getRouterZkManager().get(id);
    }

    public PeerRouterLink getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, Exception {
        return getRouterZkManager().getPeerRouterLink(routerId, peerRouterId);
    }

    /**
     * Get a list of Routers for a tenant.
     * 
     * @param tenantId
     *            UUID of tenant.
     * @return A Set of Routers
     * @throws Exception
     *             Zookeeper(or any) error.
     */
    public List<Router> list(UUID tenantId) throws Exception {
        return getRouterZkManager().list(tenantId);
    }

    /**
     * Update Router entry in MidoNet ZK data storage.
     * 
     * @param router
     *            Router object to update.
     * @throws Exception
     *             Error adding data to ZooKeeper.
     */
    public void update(Router router) throws Exception {
        getRouterZkManager().update(router);
    }

    public void delete(UUID id) throws Exception {
        getRouterZkManager().delete(id);
        // TODO: catch NoNodeException if does not exist.
    }
}
