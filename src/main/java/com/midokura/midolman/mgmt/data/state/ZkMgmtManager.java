/*
 * @(#)MgmtZkManager        1.6 21/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkManager;

/**
 * Abstract base class for MgmtZkManager.
 * 
 * @version 1.6 20 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZkMgmtManager extends ZkManager {

	protected ZkMgmtPathManager mgmtPathManager = null;
	protected ZooKeeper zooKeeper = null;

	/**
	 * Constructor.
	 * 
	 * @param zk
	 *            ZooKeeper object.
	 * @param basePath
	 *            Path to set as the base.
	 * @param mgmtBasePath
	 *            Path to set as the base for mgmt paths.
	 */
	public ZkMgmtManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
		super(new ZkDirectory(zk, "", null), basePath);
		this.mgmtPathManager = new ZkMgmtPathManager(mgmtBasePath);
		this.zooKeeper = zk;
	}

}
