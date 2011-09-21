/*
 * @(#)VifZkManager        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
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

	private ZkMgmtPathManager ZkMgmtPathManager = null;

	public VifZkManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
		super(new ZkDirectory(zk, "", null), basePath);
		this.ZkMgmtPathManager = new ZkMgmtPathManager(mgmtBasePath);
	}

	public ZkNodeEntry<UUID, VifConfig> get(UUID id) throws KeeperException,
			InterruptedException, ZkStateSerializationException {
		byte[] data = zk.get(ZkMgmtPathManager.getVifPath(id), null);
		VifConfig config = null;
		try {
			config = deserialize(data, VifConfig.class);
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not deserialize VIF " + id + " to VifConfig", e,
					VifConfig.class);
		}
		return new ZkNodeEntry<UUID, VifConfig>(id, config);
	}
}
