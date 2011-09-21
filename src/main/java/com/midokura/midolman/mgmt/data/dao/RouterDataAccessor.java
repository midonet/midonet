/*
 * @(#)RouterDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.state.RouterZkManagerProxy;
import com.midokura.midolman.mgmt.data.state.RouterZkManagerProxy.RouterMgmtConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;

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
        return getRouterZkManager().create(router.toConfig());
    }

    public PeerRouterLink createLink(LogicalRouterPort port)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, Exception {
        RouterZkManagerProxy manager = getRouterZkManager();

        // Create two logical router ports
        LogicalRouterPortConfig localPort = port.toConfig();
        LogicalRouterPortConfig peerPort = port.toPeerConfig();
        ZkNodeEntry<UUID, UUID> entry = manager.createLink(localPort, peerPort);
        PeerRouterLink peer = new PeerRouterLink();
        peer.setPortId(entry.key);
        peer.setPeerPortId(entry.value);
        peer.setPeerRouterId(peerPort.device_id);
        return peer;
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
        return Router.createRouter(id, getRouterZkManager().get(id).value);
    }

    public PeerRouterLink getPeerRouterLink(UUID routerId, UUID peerRouterId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, Exception {
        PeerRouterLink peerRouter = PeerRouterLink
                .createPeerRouterLink(getRouterZkManager().getPeerRouterLink(
                        routerId, peerRouterId));
        peerRouter.setPeerRouterId(peerRouterId);
        return peerRouter;
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
    public Router[] list(UUID tenantId) throws Exception {
        RouterZkManagerProxy manager = getRouterZkManager();
        List<Router> routers = new ArrayList<Router>();
        List<ZkNodeEntry<UUID, RouterMgmtConfig>> entries = manager
                .list(tenantId);
        for (ZkNodeEntry<UUID, RouterMgmtConfig> entry : entries) {
            routers.add(Router.createRouter(entry.key, entry.value));
        }
        return routers.toArray(new Router[routers.size()]);
    }

    /**
     * Update Router entry in MidoNet ZK data storage.
     * 
     * @param router
     *            Router object to update.
     * @throws Exception
     *             Error adding data to ZooKeeper.
     */
    public void update(UUID id, Router router) throws Exception {
        RouterZkManagerProxy manager = getRouterZkManager();
        // Only allow an update of 'name'
        ZkNodeEntry<UUID, RouterMgmtConfig> entry = manager.get(id);
        // Just allow copy of the name.
        entry.value.name = router.getName();
        manager.update(entry);
    }

    public void delete(UUID id) throws Exception {
        RouterZkManagerProxy manager = getRouterZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }
}
