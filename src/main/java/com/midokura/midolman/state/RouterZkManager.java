/*
 * @(#)RouterZkManager        1.6 11/09/08
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

import com.midokura.midolman.state.RouterDirectory.RouterConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouterZkManager {
    
    private ZkPathManager pathManager = null;
    private ZooKeeper zk = null;
    
    /**
     * Default constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public RouterZkManager(ZooKeeper zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
    }
    
    private static byte[] routerToBytes(RouterConfig tenant) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(tenant);
        out.close();
        return bos.toByteArray();
    }   
    
    /**
     * Add a new router to Zookeeper directory.
     * @param id  Router UUID
     * @param router  RouterConfig to store as data.
     * @throws InterruptedException  Thread paused too long.
     * @throws KeeperException  Zookeeper error.
     * @throws IOException  Serialization error.
     */
    public void create(UUID id, RouterConfig router) 
        throws InterruptedException, KeeperException, IOException {
        byte[] data = routerToBytes(router);
        List<Op> ops = new ArrayList<Op>();
        // Create /routers/<routerId>
        ops.add(Op.create(pathManager.getRouterPath(id), data, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Create /tenants/<tenantId>/routers/<routerId>
        ops.add(Op.create(pathManager.getTenantRouterPath(router.tenantId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Create /routers/<routerId>/ports
        ops.add(Op.create(pathManager.getRouterPortPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Create /routers/<routerId>/routing_table
        ops.add(Op.create(pathManager.getRouterRoutingTablePath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));       
        // Create /routers/<routerId>/routes
        ops.add(Op.create(pathManager.getRouterRoutesPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));    
        // Create /routers/<routerId>/rule_chains
        ops.add(Op.create(pathManager.getRouterRuleChainsPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));    
        // Create /routers/<routerId>/snat_blocks
        ops.add(Op.create(pathManager.getRouterSnatBlocksPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        zk.multi(ops);
    }
}
