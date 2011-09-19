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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.mgmt.data.state.VifZkManager.VifConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.PortDirectory.PortConfig;

/**
 * ZK port management class.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class MgmtPortZkManager extends PortZkManager {

	public static class PortMgmtConfig {

		public PortMgmtConfig() {
			super();
		}

		public PortMgmtConfig(UUID vifId) {
			super();
			this.vifId = vifId;
		}

		public UUID vifId = null;
	}

	private MgmtZkPathManager mgmtZkPathManager = null;

	public MgmtPortZkManager(Directory zk, String basePath, String mgmtBasePath) {
		super(zk, basePath);
		mgmtZkPathManager = new MgmtZkPathManager(mgmtBasePath);
	}

	public MgmtPortZkManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
		this(new ZkDirectory(zk, "", null), basePath, mgmtBasePath);
	}

	public List<Op> prepareMgmtPortCreate(
			ZkNodeEntry<UUID, PortMgmtConfig> portMgmtNode,
			ZkNodeEntry<UUID, PortDirectory.PortConfig> portNode)
			throws ZkStateSerializationException {
		return prepareMgmtPortCreate(portMgmtNode, portNode, null);
	}

	public List<Op> prepareMgmtPortCreate(
			ZkNodeEntry<UUID, PortMgmtConfig> portMgmtNode,
			ZkNodeEntry<UUID, PortDirectory.PortConfig> portNode,
			ZkNodeEntry<UUID, VifConfig> vifNode)
			throws ZkStateSerializationException {
		List<Op> ops = new ArrayList<Op>();
		try {
			ops.add(Op.create(mgmtZkPathManager.getPortPath(portMgmtNode.key),
					serialize(portMgmtNode.value), Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT));

		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize port mgmt " + portMgmtNode.key
							+ " to PortMgmtConfig", e, PortMgmtConfig.class);
		}

		// Create PortConfig
		ops.addAll(super.preparePortCreate(portNode));

		// If VIF is set, then create a new VIF entry.
		if (vifNode != null) {
			try {
				ops.add(Op.create(mgmtZkPathManager.getVifPath(vifNode.key),
						serialize(vifNode.value), Ids.OPEN_ACL_UNSAFE,
						CreateMode.PERSISTENT));

			} catch (IOException e) {
				throw new ZkStateSerializationException(
						"Could not serialize vif " + vifNode.key
								+ " to VifConfig", e, VifConfig.class);
			}
		}
		return ops;
	}

	public List<Op> prepareMgmtPortVifAttach(
			ZkNodeEntry<UUID, PortMgmtConfig> portNode,
			ZkNodeEntry<UUID, VifConfig> vifNode)
			throws ZkStateSerializationException {
		List<Op> ops = new ArrayList<Op>();
		try {
			ops.add(Op.setData(mgmtZkPathManager.getPortPath(portNode.key),
					serialize(portNode.value), -1));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize port mgmt " + portNode.key
							+ " to PortMgmtConfig", e, PortMgmtConfig.class);
		}
		try {
			ops.add(Op.create(mgmtZkPathManager.getVifPath(vifNode.key),
					serialize(vifNode.value), Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT));

		} catch (IOException e) {
			throw new ZkStateSerializationException("Could not serialize vif "
					+ vifNode.key + " to VifConfig", e, VifConfig.class);
		}
		return ops;
	}

	public List<Op> prepareMgmtPortVifDettach(UUID portId)
			throws ZkStateSerializationException, KeeperException,
			InterruptedException {

		ZkNodeEntry<UUID, PortMgmtConfig> portNode = getMgmt(portId);
		UUID vifId = portNode.value.vifId;
		portNode.value.vifId = null;

		List<Op> ops = new ArrayList<Op>();
		try {
			ops.add(Op.setData(mgmtZkPathManager.getPortPath(portNode.key),
					serialize(portNode.value), -1));
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not serialize port mgmt " + portNode.key
							+ " to PortMgmtConfig", e, PortMgmtConfig.class);
		}

		if (vifId != null) {
			ops.add(Op.delete(mgmtZkPathManager.getVifPath(vifId), -1));
		}
		return ops;
	}

	public List<Op> prepareMgmtPortDelete(
			ZkNodeEntry<UUID, PortMgmtConfig> portMgmtNode)
			throws KeeperException, InterruptedException,
			ClassNotFoundException, ZkStateSerializationException {
		List<Op> ops = new ArrayList<Op>();

		// Prepare the midolman port deletion.
		ZkNodeEntry<UUID, PortConfig> portNode = super.get(portMgmtNode.key);
		ops.addAll(super.preparePortDelete(portNode));

		// Delete the VIF attached
		ops.add(Op.delete(mgmtZkPathManager
				.getVifPath(portMgmtNode.value.vifId), -1));

		// Delete management port
		ops.add(Op.delete(mgmtZkPathManager.getPortPath(portMgmtNode.key), -1));

		return ops;
	}

	public ZkNodeEntry<UUID, PortMgmtConfig> getMgmt(UUID id)
			throws KeeperException, InterruptedException,
			ZkStateSerializationException {
		byte[] data = zk.get(mgmtZkPathManager.getPortPath(id), null);
		PortMgmtConfig config = null;
		try {
			config = deserialize(data, PortMgmtConfig.class);
		} catch (IOException e) {
			throw new ZkStateSerializationException(
					"Could not deserialize port " + id + " to PortMgmtConfig",
					e, PortMgmtConfig.class);
		}
		return new ZkNodeEntry<UUID, PortMgmtConfig>(id, config);
	}

	public UUID create(PortMgmtConfig portMgmt,
			PortDirectory.PortConfig portConfig) throws KeeperException,
			InterruptedException, ZkStateSerializationException {
		UUID id = UUID.randomUUID();
		zk.multi(prepareMgmtPortCreate(new ZkNodeEntry<UUID, PortMgmtConfig>(
				id, portMgmt),
				new ZkNodeEntry<UUID, PortConfig>(id, portConfig)));
		return id;
	}

	public void deleteMgmt(UUID id) throws InterruptedException,
			KeeperException, ZkStateSerializationException,
			ClassNotFoundException {
		this.zk.multi(prepareMgmtPortDelete(getMgmt(id)));
	}

	public void attachVif(ZkNodeEntry<UUID, PortMgmtConfig> portNode,
			ZkNodeEntry<UUID, VifConfig> vifNode) throws InterruptedException,
			KeeperException, ZkStateSerializationException,
			ClassNotFoundException {
		this.zk.multi(prepareMgmtPortVifAttach(portNode, vifNode));
	}

	public void detachVif(UUID id) throws InterruptedException,
			KeeperException, ZkStateSerializationException,
			ClassNotFoundException {
		this.zk.multi(prepareMgmtPortVifDettach(id));
	}
}
