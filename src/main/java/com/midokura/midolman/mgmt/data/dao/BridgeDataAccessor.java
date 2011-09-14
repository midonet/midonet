/*
 * @(#)BridgeDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
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

    private static Bridge convertToBridge(BridgeConfig config) {
        Bridge b = new Bridge();
        b.setName(config.name);
        b.setTenantId(config.tenantId);
        return b;
    }

    private static Bridge convertToBridge(ZkNodeEntry<UUID, BridgeConfig> entry) {
        Bridge b = convertToBridge(entry.value);
        b.setId(entry.key);
        return b;
    }
    
    private static void copyBridge(Bridge bridge, BridgeConfig config) {
        // Just allow copy of the name.
        config.name = bridge.getName();
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
        return getBridgeZkManager().create(convertToConfig(bridge));
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
        return convertToBridge(getBridgeZkManager().get(id));
    }

    public Bridge[] list(UUID tenantId) throws Exception {
        BridgeZkManager manager = getBridgeZkManager();
        List<Bridge> bridges = new ArrayList<Bridge>();
        List<ZkNodeEntry<UUID, BridgeConfig>> entries = manager.list(tenantId);
        for (ZkNodeEntry<UUID, BridgeConfig> entry : entries) {
            bridges.add(convertToBridge(entry));
        }
        return bridges.toArray(new Bridge[bridges.size()]);
    }

    public void update(UUID id, Bridge bridge) throws Exception {
        BridgeZkManager manager = getBridgeZkManager();
        // Only allow an update of 'name'
        ZkNodeEntry<UUID, BridgeConfig> entry = manager.get(id);
        copyBridge(bridge, entry.value);
        manager.update(entry);
    }
    
    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getBridgeZkManager().delete(id);
    }
}
