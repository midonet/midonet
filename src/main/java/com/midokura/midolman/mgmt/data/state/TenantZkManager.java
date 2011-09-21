/*
 * @(#)TenantZkManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkManager;

/**
 * Abstract base class for TenantZkManager.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class TenantZkManager extends ZkManager {

	private ZkMgmtPathManager ZkMgmtPathManager = null;

	/**
	 * TenantZkManager constructor.
	 * 
	 * @param zk
	 *            ZooKeeper object.
	 * @param basePath
	 *            Directory to set as the base.
	 */
	public TenantZkManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
		super(new ZkDirectory(zk, "", null), basePath);
		ZkMgmtPathManager = new ZkMgmtPathManager(mgmtBasePath);
	}

	/**
	 * Add a new tenant entry in the ZooKeeper directory.
	 * 
	 * 
	 * @param tenant
	 *            TenantConfig object to store tenant data.
	 * @throws KeeperException
	 *             General ZooKeeper exception.
	 * @throws InterruptedException
	 *             Unresponsive thread getting interrupted by another thread.
	 */
	public UUID create() throws KeeperException, InterruptedException {
		return create(null);
	}

	/**
	 * Add a new tenant entry in the ZooKeeper directory.
	 * 
	 * @param id
	 *            Tenant UUID
	 * @param tenant
	 *            TenantConfig object to store tenant data.
	 * @throws KeeperException
	 *             General ZooKeeper exception.
	 * @throws InterruptedException
	 *             Unresponsive thread getting interrupted by another thread.
	 */
	public UUID create(UUID id) throws KeeperException, InterruptedException {
		if (null == id) {
			id = UUID.randomUUID();
		}
		List<Op> ops = new ArrayList<Op>();
		ops.add(Op.create(ZkMgmtPathManager.getTenantPath(id), null,
				Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
		ops.add(Op.create(ZkMgmtPathManager.getTenantRoutersPath(id), null,
				Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
		ops.add(Op.create(ZkMgmtPathManager.getTenantBridgesPath(id), null,
				Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
		this.zk.multi(ops);
		return id;
	}

}
