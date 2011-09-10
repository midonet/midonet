/*
 * @(#)TenantZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantZkManager extends ZkManager {
    
    /**
     * TenantZkManager constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public TenantZkManager(ZooKeeper zk, String basePath) {
    	super(zk, basePath);
    }
    
    /**
     * Add a new tenant entry in the Zookeeper directory.
     * 
     * @param id  Tenant UUID
     * @param tenant  TenantConfig object to store tenant data.
     * @throws KeeperException  General Zookeeper exception.
     * @throws InterruptedException  Unresponsive thread getting
     * interrupted by another thread.
     * @throws IOException  Error while converting TenantConfig to bytes.
     */
    public void create(UUID id) 
        throws KeeperException, InterruptedException, IOException {   

        List<Op> ops = new ArrayList<Op>();
        // Create /tenants/<tenantId>
        ops.add(Op.create(pathManager.getTenantPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));        
        // Create /tenants/<routerId>/routers
        ops.add(Op.create(pathManager.getTenantRouterPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        this.zk.multi(ops);
    }
}
