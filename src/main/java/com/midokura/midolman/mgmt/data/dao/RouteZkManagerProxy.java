/*
 * @(#)RouteZkManager        1.6 11/09/05
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
public class RouteZkManagerProxy extends ZkMgmtManager {

    private RouteZkManager zkManager = null;

    public RouteZkManagerProxy(ZooKeeper zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new RouteZkManager(zk, basePath);
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
        return zkManager.create(route.toZkRoute());
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
        // TODO: Throw NotFound exception here.
        return Route.createRoute(id, zkManager.get(id).value);
    }

    private List<Route> generateRouteList(
            List<ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>> routes) {
        List<Route> routeList = new ArrayList<Route>();
        for (ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> entry : routes) {
            Route router = Route.createRoute(entry.key, entry.value);
            router.setId(entry.key);
            routeList.add(router);
        }
        return routeList;
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
    public List<Route> list(UUID routerId) throws Exception {
        return generateRouteList(zkManager.list(routerId));
    }

    public List<Route> listByPort(UUID portId) throws Exception {
        return generateRouteList(zkManager.listPortRoutes(portId));
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        zkManager.delete(id);
    }
}
