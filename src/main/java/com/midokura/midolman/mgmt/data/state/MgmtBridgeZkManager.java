/*
 * @(#)MgmtRouterZkManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to manage the bridge ZooKeeper data.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class MgmtBridgeZkManager extends BridgeZkManager {

	public static class BridgeMgmtConfig {

		public BridgeMgmtConfig() {
			super();
		}

		public BridgeMgmtConfig(UUID tenantId, String name) {
			super();
			this.tenantId = tenantId;
			this.name = name;
		}

		public UUID tenantId;
		public String name;
	}

	private MgmtZkPathManager mgmtZkPathManager = null;

	public MgmtBridgeZkManager(ZooKeeper zk, String basePath,
			String mgmtBasePath) {
		super(zk, basePath);
		mgmtZkPathManager = new MgmtZkPathManager(mgmtBasePath);
	}

	public List<Op> prepareBridgeCreate(
			ZkNodeEntry<UUID, BridgeMgmtConfig> bridgeMgmtNode,
			ZkNodeEntry<UUID, BridgeConfig> bridgeNode)
			throws ZkStateSerializationException, KeeperException,
			InterruptedException {
		List<Op> ops = new ArrayList<Op>();

		// Add an entry.
		try {
			ops.add(Op.create(mgmtZkPathManager
					.getBridgePath(bridgeMgmtNode.key),
					serialize(bridgeMgmtNode.value), Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize BridgeMgmtConfig", e,
					BridgeMgmtConfig.class);
		}

		// Add under tenant.
		ops.add(Op.create(mgmtZkPathManager.getTenantBridgePath(
				bridgeMgmtNode.value.tenantId, bridgeMgmtNode.key), null,
				Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

		ops.addAll(super.prepareBridgeCreate(bridgeNode));
		return ops;
	}

	public UUID create(BridgeMgmtConfig bridgeMgmtConfig)
			throws InterruptedException, KeeperException,
			ZkStateSerializationException {
		UUID id = UUID.randomUUID();
		ZkNodeEntry<UUID, BridgeMgmtConfig> bridgeMgmtNode = new ZkNodeEntry<UUID, BridgeMgmtConfig>(
				id, bridgeMgmtConfig);
		ZkNodeEntry<UUID, BridgeConfig> bridgeNode = new ZkNodeEntry<UUID, BridgeConfig>(
				id, new BridgeConfig());
		zk.multi(prepareBridgeCreate(bridgeMgmtNode, bridgeNode));
		return id;
	}

	public ZkNodeEntry<UUID, BridgeMgmtConfig> getMgmt(UUID id)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		byte[] data = zk.get(mgmtZkPathManager.getBridgePath(id), null);
		BridgeMgmtConfig config = null;
		try {
			config = deserialize(data, BridgeMgmtConfig.class);
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not deserialize bridge " + id
							+ " to BridgeMgmtConfig", e, BridgeMgmtConfig.class);
		}
		return new ZkNodeEntry<UUID, BridgeMgmtConfig>(id, config);
	}

	public List<ZkNodeEntry<UUID, BridgeMgmtConfig>> listMgmt(UUID tenantId)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		List<ZkNodeEntry<UUID, BridgeMgmtConfig>> result = new ArrayList<ZkNodeEntry<UUID, BridgeMgmtConfig>>();
		Set<String> bridgeIds = zk.getChildren(mgmtZkPathManager
				.getTenantBridgesPath(tenantId), null);
		for (String bridgeId : bridgeIds) {
			// For now, get each one.
			result.add(getMgmt(UUID.fromString(bridgeId)));
		}
		return result;
	}

	public void update(ZkNodeEntry<UUID, BridgeMgmtConfig> entry)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		// Update any version for now.
		try {
			zk.update(mgmtZkPathManager.getBridgePath(entry.key),
					serialize(entry.value));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize bridge mgmt " + entry.key
							+ " to BridgeMgmtConfig", e, BridgeMgmtConfig.class);
		}
	}

	public List<Op> prepareBridgeDelete(
			ZkNodeEntry<UUID, BridgeMgmtConfig> bridgeMgmtNode,
			ZkNodeEntry<UUID, BridgeConfig> bridgeNode) throws KeeperException,
			InterruptedException, ZkStateSerializationException {

		List<Op> ops = new ArrayList<Op>();
		try {
			ops.addAll(super.prepareBridgeDelete(bridgeNode));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not deserialize BridgeConfig", e, BridgeConfig.class);
		}

		// Delete the tenant bridge entry
		ops.add(Op.delete(mgmtZkPathManager.getTenantBridgePath(
				bridgeMgmtNode.value.tenantId, bridgeMgmtNode.key), -1));
		ops.add(Op.delete(mgmtZkPathManager.getBridgePath(bridgeMgmtNode.key),
				-1));

		return ops;
	}

	public void delete(UUID id) throws InterruptedException, KeeperException,
			ZkStateSerializationException, IOException {
		this.zk.multi(prepareBridgeDelete(getMgmt(id), get(id)));
	}
}
