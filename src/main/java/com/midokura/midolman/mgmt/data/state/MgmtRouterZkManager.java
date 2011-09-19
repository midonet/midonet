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

import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

public class MgmtRouterZkManager extends RouterZkManager {

	public static class RouterMgmtConfig {

		public RouterMgmtConfig() {
			super();
		}

		public RouterMgmtConfig(UUID tenantId, String name) {
			super();
			this.tenantId = tenantId;
			this.name = name;
		}

		public UUID tenantId;
		public String name;
	}

	private MgmtZkPathManager mgmtZkPathManager = null;

	public MgmtRouterZkManager(ZooKeeper zk, String basePath,
			String mgmtBasePath) {
		super(zk, basePath);
		mgmtZkPathManager = new MgmtZkPathManager(mgmtBasePath);
	}

	public List<Op> prepareRouterCreate(
			ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode)
			throws ZkStateSerializationException, KeeperException,
			InterruptedException {
		List<Op> ops = new ArrayList<Op>();
		try {
			ops.add(Op.create(mgmtZkPathManager
					.getRouterPath(routerMgmtNode.key),
					serialize(routerMgmtNode.value), Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize RouterMgmtConfig", e,
					RouterMgmtConfig.class);
		}

		// Add under tenant.
		ops.add(Op.create(mgmtZkPathManager.getTenantRouterPath(
				routerMgmtNode.value.tenantId, routerMgmtNode.key), null,
				Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

		ops.addAll(super.prepareRouterCreate(routerMgmtNode.key));
		return ops;
	}

	public UUID create(RouterMgmtConfig routerMgmtConfig)
			throws InterruptedException, KeeperException,
			ZkStateSerializationException {
		UUID id = UUID.randomUUID();
		ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode = new ZkNodeEntry<UUID, RouterMgmtConfig>(
				id, routerMgmtConfig);
		zk.multi(prepareRouterCreate(routerMgmtNode));
		return id;
	}

	public ZkNodeEntry<UUID, RouterMgmtConfig> getMgmt(UUID id)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		byte[] data = zk.get(mgmtZkPathManager.getRouterPath(id), null);
		RouterMgmtConfig config = null;
		try {
			config = deserialize(data, RouterMgmtConfig.class);
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not deserialize router " + id
							+ " to RouterMgmtConfig", e, RouterMgmtConfig.class);
		}
		return new ZkNodeEntry<UUID, RouterMgmtConfig>(id, config);
	}

	public List<ZkNodeEntry<UUID, RouterMgmtConfig>> listMgmt(UUID tenantId)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		List<ZkNodeEntry<UUID, RouterMgmtConfig>> result = new ArrayList<ZkNodeEntry<UUID, RouterMgmtConfig>>();
		Set<String> routerIds = zk.getChildren(mgmtZkPathManager
				.getTenantRoutersPath(tenantId), null);
		for (String routerId : routerIds) {
			// For now, get each one.
			result.add(getMgmt(UUID.fromString(routerId)));
		}
		return result;
	}

	public void update(ZkNodeEntry<UUID, RouterMgmtConfig> entry)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		// Update any version for now.
		try {
			zk.update(mgmtZkPathManager.getRouterPath(entry.key),
					serialize(entry.value));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize router mgmt " + entry.key
							+ " to RouterMgmtConfig", e, RouterMgmtConfig.class);
		}
	}

	public List<Op> prepareRouterDelete(
			ZkNodeEntry<UUID, RouterMgmtConfig> routerMgmtNode)
			throws KeeperException, InterruptedException,
			ClassNotFoundException, ZkStateSerializationException {

		List<Op> ops = new ArrayList<Op>();
		ops.addAll(super.prepareRouterDelete(routerMgmtNode.key));

		// Delete the tenant router entry
		ops.add(Op.delete(mgmtZkPathManager.getTenantRouterPath(
				routerMgmtNode.value.tenantId, routerMgmtNode.key), -1));
		ops.add(Op.delete(mgmtZkPathManager.getRouterPath(routerMgmtNode.key),
				-1));
		return ops;
	}

	public void delete(UUID id) throws InterruptedException, KeeperException,
			ZkStateSerializationException, IOException, ClassNotFoundException {
		this.zk.multi(prepareRouterDelete(getMgmt(id)));
	}

}
