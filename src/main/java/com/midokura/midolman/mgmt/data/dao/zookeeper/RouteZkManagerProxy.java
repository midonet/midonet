/*
 * @(#)RouteZkManager        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Data access class for routes.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteZkManagerProxy extends ZkMgmtManager implements RouteDao,
        OwnerQueryable {

    private RouteZkManager zkManager = null;

    public RouteZkManagerProxy(Directory zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new RouteZkManager(zk, basePath);
    }

    /**
     * Add Route object to Zookeeper directories.
     *
     * @param route
     *            Route object to add.
     * @throws StateAccessException
     * @throws ZkStateSerializationException
     */
    @Override
    public UUID create(Route route) throws StateAccessException {
        return zkManager.create(route.toZkRoute());
    }

    /**
     * Get a Route for the given ID.
     *
     * @param id
     *            Route ID to search.
     * @return Route object with the given ID.
     * @throws StateAccessException
     * @throws ZkStateSerializationException
     */
    @Override
    public Route get(UUID id) throws StateAccessException {
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
     * @throws StateAccessException
     * @throws ZkStateSerializationException
     */
    @Override
    public List<Route> list(UUID routerId) throws StateAccessException {
        return generateRouteList(zkManager.list(routerId));
    }

    @Override
    public List<Route> listByPort(UUID portId) throws StateAccessException {
        return generateRouteList(zkManager.listPortRoutes(portId));
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        zkManager.delete(id);
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        Route route = get(id);
        OwnerQueryable manager = new RouterZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        return manager.getOwner(route.getRouterId());
    }
}
