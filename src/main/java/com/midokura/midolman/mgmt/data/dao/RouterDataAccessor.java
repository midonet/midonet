/*
 * @(#)RouterDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.TenantDirectory;
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
     * Default constructor 
     * 
     * @param zkConn Zookeeper connection string
     */
    public RouterDataAccessor(String zkConn) {
        super(zkConn);
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
     * Get a Router for the given ID.
     * 
     * @param   id  Router ID to search.
     * @return  Router object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Router find(UUID id) throws Exception {
        RouterDirectory dir = getRouterDirectory();
        RouterConfig config = dir.getRouter(id);
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
        TenantDirectory dir = getTenantDirectory();
        Set<Router> routers = new HashSet<Router>();
        Set<RouterConfig> configs = dir.getRouters(tenantId);
        for(RouterConfig config : configs) {
            routers.add(convertToRouter(config));
        }
        return routers.toArray(new Router[routers.size()]);
    }
}
