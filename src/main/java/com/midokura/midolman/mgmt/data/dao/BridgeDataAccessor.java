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
import com.midokura.midolman.mgmt.data.state.MgmtBridgeZkManager;
import com.midokura.midolman.mgmt.data.state.MgmtBridgeZkManager.BridgeMgmtConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;

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

	private MgmtBridgeZkManager getBridgeZkManager() throws Exception {
		ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
		return new MgmtBridgeZkManager(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
	}

	private static BridgeMgmtConfig convertToConfig(Bridge bridge) {
		return new BridgeMgmtConfig(bridge.getTenantId(), bridge.getName());
	}

	private static Bridge convertToBridge(BridgeMgmtConfig config) {
		Bridge b = new Bridge();
		b.setName(config.name);
		b.setTenantId(config.tenantId);
		return b;
	}

	private static Bridge convertToBridge(
			ZkNodeEntry<UUID, BridgeMgmtConfig> entry) {
		Bridge b = convertToBridge(entry.value);
		b.setId(entry.key);
		return b;
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
		return convertToBridge(getBridgeZkManager().getMgmt(id));
	}

	public Bridge[] list(UUID tenantId) throws Exception {
		MgmtBridgeZkManager manager = getBridgeZkManager();
		List<Bridge> bridges = new ArrayList<Bridge>();
		List<ZkNodeEntry<UUID, BridgeMgmtConfig>> entries = manager.listMgmt(tenantId);
		for (ZkNodeEntry<UUID, BridgeMgmtConfig> entry : entries) {
			bridges.add(convertToBridge(entry));
		}
		return bridges.toArray(new Bridge[bridges.size()]);
	}

	public void update(UUID id, Bridge bridge) throws Exception {
		MgmtBridgeZkManager manager = getBridgeZkManager();
		// Only allow an update of 'name'
		ZkNodeEntry<UUID, BridgeMgmtConfig> entry = manager.getMgmt(id);
		// Just allow copy of the name.
		entry.value.name = bridge.getName();
		manager.update(entry);
	}

	public void delete(UUID id) throws Exception {
		// TODO: catch NoNodeException if does not exist.
		getBridgeZkManager().delete(id);
	}
}
