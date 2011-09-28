/*
 * @(#)TenantZkManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
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
    public TenantZkManager(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath);
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
    public UUID create() throws StateAccessException {
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
    public UUID create(UUID id) throws StateAccessException {
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
        ops.add(Op.create(ZkMgmtPathManager.getTenantRouterNamesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(ZkMgmtPathManager.getTenantBridgeNamesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        multi(ops);
        return id;
    }

}
