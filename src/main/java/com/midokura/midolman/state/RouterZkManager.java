/*
 * @(#)RouterZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
public class RouterZkManager extends ZkManager {
    
    /**
     * RouterZkManager constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public RouterZkManager(ZooKeeper zk, String basePath) {
    	super(zk, basePath);
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
        List<Op> ops = new ArrayList<Op>();
        // Create /routers/<routerId>
        ops.add(Op.create(pathManager.getRouterPath(id), serialize(router), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Create /tenants/<tenantId>/routers/<routerId>
        ops.add(Op.create(pathManager.getTenantRouterPath(router.tenantId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // Create /routers/<routerId>/ports
        ops.add(Op.create(pathManager.getRouterPortsPath(id), null, 
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

    /**
     * Update a router data.
     * @param id  Router UUID
     * @param router  RouterConfig object.
     * @throws IOException  Serialization error.
     * @throws InterruptedException 
     * @throws KeeperException 
     */
    public void update(UUID id, RouterConfig router) 
    		throws IOException, KeeperException, InterruptedException {
        // Update any version for now.
        zk.setData(pathManager.getRouterPath(id), serialize(router), -1);
    }
    
    /**
     * Get a RouterConfig object.
     * @param id  Router UUID,
     * @return  A RouterConfig
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public RouterConfig get(UUID id) 
            throws KeeperException, InterruptedException,
                IOException, ClassNotFoundException {
        byte[] data = zk.getData(pathManager.getRouterPath(id), null, null);
        return deserialize(data, RouterConfig.class);      
    }

    /**
     * Get a list of RouterConfig objects for a tenant.
     * @param tenantId  Tenant UUID,
     * @return  An array of RouterConfigs
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public HashMap<UUID, RouterConfig> list(UUID tenantId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        HashMap<UUID, RouterConfig> configs = 
            new HashMap<UUID, RouterConfig>();
        List<String> routerIds = zk.getChildren(
                pathManager.getTenantRoutersPath(tenantId), null);
        for (String routerId : routerIds) {
            // For now get each one.
            UUID id = UUID.fromString(routerId);
            configs.put(id, get(id));
        }
        return configs;
    }
}
