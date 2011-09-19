/*
 * @(#)VifZkManager        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * ZK VIF management class.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class VifZkManager extends ZkManager {

	public static class VifConfig {

		public VifConfig() {
			super();
		}

		public VifConfig(UUID portId) {
			super();
			this.portId = portId;
		}

		public UUID portId;
	}

	private MgmtZkPathManager mgmtZkPathManager = null;

	public VifZkManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
		super(new ZkDirectory(zk, "", null), basePath);
		mgmtZkPathManager = new MgmtZkPathManager(mgmtBasePath);
	}

	/**
	 * Add a new VIF entry in the ZooKeeper directory.
	 * 
	 * 
	 * @param vif
	 *            VifConfig object to store VIF data.
	 * @throws KeeperException
	 *             General ZooKeeper exception.
	 * @throws InterruptedException
	 *             Unresponsive thread getting interrupted by another thread.
	 * @throws ZkStateSerializationException
	 */
	public UUID create(VifConfig vif) throws KeeperException,
			InterruptedException, ZkStateSerializationException {
		return create(null, vif);
	}

	/**
	 * Add a new VIF entry in the ZooKeeper directory.
	 * 
	 * @param id
	 *            VIF UUID
	 * @param vif
	 *            VifConfig object to store tenant data.
	 * @throws KeeperException
	 *             General ZooKeeper exception.
	 * @throws InterruptedException
	 *             Unresponsive thread getting interrupted by another thread.
	 * @throws ZkStateSerializationException
	 */
	public UUID create(UUID id, VifConfig vif) throws KeeperException,
			InterruptedException, ZkStateSerializationException {
		if (null == id) {
			id = UUID.randomUUID();
		}
		List<Op> ops = new ArrayList<Op>();
		try {
			ops.add(Op.setData(mgmtZkPathManager.getVifPath(id),
					serialize(vif), -1));

		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize VifConfig", e, VifConfig.class);
		}
		return id;
	}

}
