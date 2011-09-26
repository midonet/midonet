/*
 * @(#)BridgeDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.state.BridgeZkManagerProxy;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for bridge.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class BridgeDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public BridgeDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private BridgeZkManagerProxy getBridgeZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new BridgeZkManagerProxy(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    /**
     * Add a JAXB object the ZK directories.
     * 
     * @param bridge
     *            Bridge object to add.
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public UUID create(Bridge bridge) throws Exception {
        return getBridgeZkManager().create(bridge);
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            Bridge UUID to fetch..
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public Bridge get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return getBridgeZkManager().get(id);
    }

    public List<Bridge> list(UUID tenantId) throws Exception {
        return getBridgeZkManager().list(tenantId);
    }

    public void update(Bridge bridge) throws Exception {
        getBridgeZkManager().update(bridge);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getBridgeZkManager().delete(id);
    }
}
