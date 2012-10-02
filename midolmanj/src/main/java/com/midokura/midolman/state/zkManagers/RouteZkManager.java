/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.DirectoryCallbackFactory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;

/**
 * Class to manage the routing ZooKeeper data.
 */
public class RouteZkManager extends ZkManager {


    private final Functor<Set<byte[]>, Set<Route>> byteArrayToRoutesSetMapper =
        new Functor<Set<byte[]>, Set<Route>>(){
            @Override
            public Set<Route> apply(Set<byte[]> arg0) {
                return CollectionFunctors.map(arg0, byteToRouteSerializer,
                                              new HashSet<Route>());
            }
        };

    private final Functor<byte[], Route> byteToRouteSerializer =
        new Functor<byte[], Route>() {
            @Override
            public Route apply(byte[] arg0) {
                try {
                    return serializer.deserialize(arg0, Route.class);
                } catch (Exception e) {
                    log.error("Error deserializing route {}", arg0);
                    return null;
                }
            }
        };

    private final static Logger log = LoggerFactory
            .getLogger(RouteZkManager.class);

    /**
     * Initializes a RouteZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public RouteZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    private List<String> getSubDirectoryRoutePaths(UUID id, Route config)
            throws StateAccessException {
        // Determine whether to add the Route data under routers or ports.
        // Router routes and logical port routes should also be added to the
        // routing table.
        List<String> ret = new ArrayList<String>();
        if (config.nextHop.equals(Route.NextHop.PORT)) {
            // Check what kind of port this is.
            PortZkManager portZkManager = new PortZkManager(zk,
                    paths.getBasePath());
            PortDirectory.RouterPortConfig port = portZkManager.get(
                    config.nextHopPort, PortDirectory.RouterPortConfig.class);

            ret.add(paths.getPortRoutePath(config.nextHopPort, id));
            // If it's a logical port, add the route to the routing table.
            if (port instanceof PortDirectory.LogicalRouterPortConfig)
                ret.add(getRouteInRoutingTable(config));
        } else {
            ret.add(paths.getRouterRoutePath(config.routerId, id));
            // Add the route to the routing table.
            ret.add(getRouteInRoutingTable(config));
        }
        return ret;
    }

    private String getRouteInRoutingTable(Route rt)
            throws ZkStateSerializationException {
        String rtStr = new String(serializer.serialize(rt));
        String rtable = paths.getRouterRoutingTablePath(rt.routerId);
        StringBuilder sb = new StringBuilder(rtable).append("/").append(rtStr);
        return sb.toString();
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new route.
     *
     * @param id
     *            Route ID
     * @param config
     *            Route ZK config object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<Op> prepareRouteCreate(UUID id, Route config, boolean persistent)
            throws StateAccessException {
        CreateMode mode = persistent ? CreateMode.PERSISTENT
                : CreateMode.EPHEMERAL;
        // TODO(pino): sanity checking on route - egress belongs to device.
        List<Op> ops = new ArrayList<Op>();
        // Add to root
        ops.add(Op.create(paths.getRoutePath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE, mode));

        // Add under port or router. Router routes and logical port routes
        // should also be added to the routing table.
        for (String path : getSubDirectoryRoutePaths(id, config)) {
            ops.add(Op.create(path, null, Ids.OPEN_ACL_UNSAFE, mode));
        }
        return ops;
    }

    public List<Op> prepareRouteCreate(UUID id, Route config)
            throws StateAccessException {
        return prepareRouteCreate(id, config, true);
    }

    /**
     * Constructs a list of operations to perform in a route deletion.
     *
     * @param id
     *            Route ID
     * @return A list of Op objects representing the operations to perform.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<Op> prepareRouteDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String routePath = paths.getRoutePath(id);
        log.debug("Preparing to delete: " + routePath);
        ops.add(Op.delete(routePath, -1));
        Route config = get(id);
        for (String path : getSubDirectoryRoutePaths(id, config)) {
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
        }
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new route entry.
     *
     * @param route
     *            Route object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public UUID create(Route route, boolean persistent)
            throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareRouteCreate(id, route, persistent));
        return id;
    }

    public UUID create(Route route) throws StateAccessException {
        return create(route, true);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a route with the given ID.
     *
     * @param id
     *            The ID of the route.
     * @return Route object found.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public Route get(UUID id) throws StateAccessException {
        return get(id, null);
    }

    public void asyncGet(UUID id, final DirectoryCallback<Route> routeDirectoryCallback){
        zk.asyncGet(paths.getRoutePath(id),
                    DirectoryCallbackFactory.transform(
                        routeDirectoryCallback, byteToRouteSerializer), null);
    }

    public void asyncMultiRoutesGet(Set<UUID> ids,
                                    final DirectoryCallback<Set<Route>> routesCallback){
        Set<String> pathIds = new HashSet<String>();
        for(UUID id: ids){
            pathIds.add(paths.getRoutePath(id));
        }
        zk.asyncMultiPathGet(pathIds, DirectoryCallbackFactory.transform(
            routesCallback, byteArrayToRoutesSetMapper));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a route with the given ID
     * and sets a watcher on the node.
     *
     * @param id
     *            The ID of the route.
     * @param watcher
     *            The watcher that gets notified when there is a change in the
     *            node.
     * @return Route object found.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public Route get(UUID id, Runnable watcher) throws StateAccessException {
        byte[] routeData = get(paths.getRoutePath(id), watcher);
        return serializer.deserialize(routeData, Route.class);
    }

    /**
     * Gets a list of ZooKeeper router nodes belonging under the router
     * directory with the given ID.
     *
     * @param routerId
     *            The ID of the router to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the routes for this
     *            router.
     * @return A list of route IDs.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<UUID> listRouterRoutes(UUID routerId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> routeIds = getChildren(
                paths.getRouterRoutesPath(routerId), watcher);
        for (String routeId : routeIds) {
            result.add(UUID.fromString(routeId));
        }
        return result;
    }

    public List<UUID> listPortRoutes(UUID portId) throws StateAccessException {
        return listPortRoutes(portId, null);
    }

    /**
     * Gets a list of ZooKeeper route nodes belonging under the port directory
     * with the given ID.
     *
     * @param portId
     *            The ID of the port to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the routes for this port.
     * @return A list of route IDs.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<UUID> listPortRoutes(UUID portId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> routeIds = getChildren(
                paths.getPortRoutesPath(portId), watcher);
        for (String routeId : routeIds) {
            result.add(UUID.fromString(routeId));
        }
        return result;
    }

    public void listPortRoutesAsync(UUID portId,
                                    final DirectoryCallback<Set<UUID>> listPortRoutesCallback,
                                    Directory.TypedWatcher watcher){

        zk.asyncGetChildren(
            paths.getPortRoutesPath(portId),
            DirectoryCallbackFactory.transform(
                listPortRoutesCallback,
                new Functor<Set<String>,Set<UUID>>() {
                    @Override
                    public Set<UUID> apply(Set<String> arg0) {
                        return CollectionFunctors.map(
                            arg0, strToUUIDMapper, new HashSet<UUID>());
                    }
                }
            ), watcher);

    }

    /**
     * Gets a list of ZooKeeper route nodes belonging to a router with the given
     * ID.
     *
     * @param routerId
     *            The ID of the router to find the routes of.
     * @return A list of route IDs.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<UUID> list(UUID routerId) throws StateAccessException {
        List<UUID> routes = listRouterRoutes(routerId, null);
        Set<String> portIds = getChildren(
                paths.getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
            // For each MaterializedRouterPort, process it. Needs optimization.
            UUID portUUID = UUID.fromString(portId);
            byte[] data = get(paths.getPortPath(portUUID), null);
            PortConfig port = serializer.deserialize(data, PortConfig.class);
            if (!(port instanceof PortDirectory.RouterPortConfig)) {
                continue;
            }

            List<UUID> portRoutes = listPortRoutes(portUUID);
            routes.addAll(portRoutes);
        }
        return routes;
    }

    /***
     * Deletes a route and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the route to delete.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public void delete(UUID id) throws StateAccessException {
        multi(prepareRouteDelete(id));
    }

    public boolean exists(UUID id) throws StateAccessException {

        String path = paths.getRoutePath(id);
        return exists(path);
    }
}
