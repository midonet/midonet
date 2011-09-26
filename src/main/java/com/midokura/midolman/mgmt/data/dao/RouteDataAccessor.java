/*
 * @(#)RouteDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for routes.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public RouteDataAccessor(ZooKeeper zkConn, String rootPath,
            String mgmtRootPath) {
        super(zkConn, rootPath, mgmtRootPath);
    }

    private RouteZkManager getRouteZkManager() throws Exception {
        return new RouteZkManager(zkConn, zkRoot);
    }

    /**
     * Add Route object to Zookeeper directories.
     * 
     * @param route
     *            Route object to add.
     * @throws Exception
     *             Error adding data to Zookeeper.
     */
    public UUID create(Route route) throws Exception {
        return getRouteZkManager().create(route.toZkRoute());
    }

    /**
     * Get a Route for the given ID.
     * 
     * @param id
     *            Route ID to search.
     * @return Route object with the given ID.
     * @throws Exception
     *             Error getting data to Zookeeper.
     */
    public Route get(UUID id) throws Exception {
        RouteZkManager manager = getRouteZkManager();
        ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> rt = manager
                .get(id);
        // TODO: Throw NotFound exception here.
        return Route.createRoute(id, rt.value);
    }

    /**
     * Get a list of routes of a router.
     * 
     * @param routerId
     *            UUID of router.
     * @return A list of router.
     * @throws Exception
     *             Zookeeper(or any) error.
     */
    public Route[] list(UUID routerId) throws Exception {
        RouteZkManager manager = getRouteZkManager();
        List<Route> routes = new ArrayList<Route>();
        List<ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>> zkRoutes = manager
                .list(routerId);
        for (ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> entry : zkRoutes) {
            Route router = Route.createRoute(entry.key, entry.value);
            router.setId(entry.key);
            routes.add(router);
        }
        return routes.toArray(new Route[routes.size()]);
    }

    public void delete(UUID id) throws Exception {
        RouteZkManager manager = getRouteZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }
}
