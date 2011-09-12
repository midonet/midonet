/*
 * @(#)BridgeDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

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
    public BridgeDataAccessor(String zkConn) {
        super(zkConn);
    }

    private BridgeZkManager getBridgeZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);
        return new BridgeZkManager(conn.getZooKeeper(), "/midolman");
    }

    private static BridgeConfig convertToConfig(Bridge bridge) {
        return new BridgeConfig(bridge.getName(), bridge.getTenantId());
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
        BridgeZkManager manager = getBridgeZkManager();
        ZkNodeEntry<UUID, BridgeConfig> entry = manager
                .create(convertToConfig(bridge));
        return entry.key;
    }
}
