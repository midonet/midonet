/*
 * @(#)MgmtZkManager        1.6 21/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkManager;

/**
 * Abstract base class for MgmtZkManager.
 * 
 * @version 1.6 20 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZkMgmtManager extends ZkManager {

    protected ZkMgmtPathManager mgmtPathManager = null;

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
    public ZkMgmtManager(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath);
        this.mgmtPathManager = new ZkMgmtPathManager(mgmtBasePath);
    }
}
