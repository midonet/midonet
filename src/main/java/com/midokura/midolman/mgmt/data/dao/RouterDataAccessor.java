/*
 * @(#)RouterDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.RouterDirectory.RouterConfig;

/**
 * Data access class for router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouterDataAccessor extends DataAccessor {
    /*
     * Implements CRUD operations on Router.
     */
    
    /**
     * Constructor 
     * 
     * @param zkConn Zookeeper connection string
     */
    public RouterDataAccessor(String zkConn) {
        super(zkConn);
    }
    
    private RouterZkManager getRouterZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new RouterZkManager(conn.getZooKeeper(), "/midolman");
    } 
    
    private static RouterConfig convertToConfig(Router router) {
        return new RouterConfig(router.getName(), router.getTenantId());
    }

    private static Router convertToRouter(RouterConfig config) {
        Router router = new Router();
        router.setName(config.name);
        router.setTenantId(config.tenantId);
        return router;
    }
    
    /**
     * Add Router object to Zookeeper directories.
     * 
     * @param   router  Router object to add.
     * @throws  Exception  Error adding data to Zookeeper.
     */
    public void create(Router router) throws Exception {
        RouterConfig config = convertToConfig(router);
        RouterZkManager manager = getRouterZkManager();
        manager.create(router.getId(), config);
    }

    /**
     * Update Router entry in ZooKeeper.
     * 
     * @param   router  Router object to update.
     * @throws  Exception  Error adding data to ZooKeeper.
     */
    public void update(UUID id, Router router) throws Exception {
        RouterConfig config = convertToConfig(router);
        RouterZkManager manager = getRouterZkManager();
        manager.update(id, config);
    }
    
    public void delete(UUID id) throws Exception {
        RouterZkManager manager = getRouterZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }
    
    /**
     * Get a Router for the given ID.
     * 
     * @param   id  Router ID to search.
     * @return  Router object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Router get(UUID id) throws Exception {
        RouterZkManager manager = getRouterZkManager();
        RouterConfig config = manager.get(id);
        // TODO: Throw NotFound exception here.
        Router router = convertToRouter(config);
        router.setId(id);
        return router;
    }
    
    /**
     * Get a list of Routers for a tenant.
     * 
     * @param tenantId  UUID of tenant.
     * @return  A Set of Routers
     * @throws Exception  Zookeeper(or any) error.
     */
    public Router[] list(UUID tenantId) throws Exception {
        RouterZkManager manager = getRouterZkManager();
        List<Router> routers = new ArrayList<Router>();
        HashMap<UUID, RouterConfig> configs = manager.list(tenantId);
        for (Map.Entry<UUID, RouterConfig> entry : configs.entrySet()) {
            Router router = convertToRouter(entry.getValue());
            router.setId(entry.getKey());
            routers.add(router);            
        }
        return routers.toArray(new Router[routers.size()]);
    }
}
