/*
 * @(#)TenantZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.state.TenantDirectory.TenantConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class TenantZkManager {

    private ZkPathManager pathManager = null;
    private ZooKeeper zk = null;
    
    /**
     * Default constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public TenantZkManager(ZooKeeper zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
    }
    
    private byte[] tenantToBytes(TenantConfig tenant) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(tenant);
        out.close();
        return bos.toByteArray();
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
    public void create(UUID id, TenantConfig tenant) 
        throws KeeperException, InterruptedException, IOException {
        // Add Routers child node.
        byte[] data = tenantToBytes(tenant);
        
        List<Op> ops = new ArrayList<Op>();
        // Create /tenants/<tenantId>
        ops.add(Op.create(pathManager.getTenantPath(id), data, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));        
        // Create /tenants/<routerId>/routers
        ops.add(Op.create(pathManager.getTenantRouterPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        this.zk.multi(ops);
    }    
}
