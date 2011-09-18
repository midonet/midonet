/*
 * @(#)TenantZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * ZK tenant management class.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class TenantZkManager extends ZkManager {

    /**
     * TenantZkManager constructor.
     * 
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public TenantZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public TenantZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
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
        ops.add(Op.create(pathManager.getTenantPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getTenantRoutersPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getTenantBridgesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        this.zk.multi(ops);
        return id;
    }
}
