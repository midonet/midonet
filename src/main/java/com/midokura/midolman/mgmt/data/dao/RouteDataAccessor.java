/*
 * @(#)RouteDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.util.Net;

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
    public RouteDataAccessor(String zkConn) {
        super(zkConn);
    }

    private RouteZkManager getRouteZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);
        return new RouteZkManager(conn.getZooKeeper(), "/midolman");
    }

    private static com.midokura.midolman.layer3.Route convertToZkRoute(
            Route route) {
        NextHop nextHop = null;
        String type = route.getType();
        if (type.equals(Route.Reject)) {
            nextHop = NextHop.REJECT;
        } else if (type.equals(Route.BlackHole)) {
            nextHop = NextHop.BLACKHOLE;
        } else {
            nextHop = NextHop.PORT;
        }

        return new com.midokura.midolman.layer3.Route(Net
                .convertAddressToInt(route.getSrcNetworkAddr()), route
                .getSrcNetworkLength(), Net.convertAddressToInt(route
                .getDstNetworkAddr()), route.getDstNetworkLength(), nextHop,
                route.getNextHopPort(), Net.convertAddressToInt(route
                        .getNextHopGateway()), route.getWeight(), "some,attr", // FIXME:
                // Some
                // reason
                // this
                // is
                // required.
                route.getRouterId());
    }

    private static Route convertToRoute(com.midokura.midolman.layer3.Route rt) {
        Route route = new Route();
        route.setDstNetworkAddr(Net.convertAddressToString(rt.dstNetworkAddr));
        route.setDstNetworkLength(rt.dstNetworkLength);
        route.setNextHopGateway(Net.convertAddressToString(rt.nextHopGateway));
        route.setNextHopPort(rt.nextHopPort);
        route.setSrcNetworkAddr(Net.convertAddressToString(rt.srcNetworkAddr));
        route.setSrcNetworkLength(rt.srcNetworkLength);
        route.setWeight(rt.weight);
        route.setRouterId(rt.routerId);

        if (rt.nextHop == NextHop.BLACKHOLE) {
            route.setType(Route.BlackHole);
        } else if (rt.nextHop == NextHop.REJECT) {
            route.setType(Route.Reject);
        } else {
            route.setType(Route.Normal);
        }
        return route;
    }

    /**
     * Add Route object to Zookeeper directories.
     * 
     * @param route
     *            Route object to add.
     * @throws Exception
     *             Error adding data to Zookeeper.
     */
    public void create(Route route) throws Exception {
        com.midokura.midolman.layer3.Route rt = convertToZkRoute(route);
        RouteZkManager manager = getRouteZkManager();
        manager.create(route.getId(), route.getRouterId(), rt);
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
        com.midokura.midolman.layer3.Route rt = manager.get(id);
        // TODO: Throw NotFound exception here.
        Route route = convertToRoute(rt);
        route.setId(id);
        return route;
    }

    public void delete(UUID id) throws Exception {
        RouteZkManager manager = getRouteZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
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
            Route router = convertToRoute(entry.value);
            router.setId(entry.key);
            routes.add(router);
        }
        return routes.toArray(new Route[routes.size()]);
    }
}
