/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.state.zkManagers;

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

import org.midonet.midolman.layer3.Route;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

/**
 * Class to manage the routing ZooKeeper data.
 */
public class RouteZkManager extends AbstractZkManager {


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
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public RouteZkManager(ZkManager zk, PathBuilder paths,
                          Serializer serializer) {
        super(zk, paths, serializer);
    }

    /**
     *
     * @param id         The UUID of the route
     * @param rtConfig   The configuration of the route
     * @param portConfig If the route is LOCAL, the port has not been created
     *                   yet, but the config is needed because it determines
     *                   where the route is stored. This is null on route
     *                   create, needs to be fetched.
     * @return
     * @throws StateAccessException
     */
    private List<String> getSubDirectoryRoutePaths(
            UUID id, Route rtConfig, PortDirectory.RouterPortConfig portConfig)
            throws StateAccessException, SerializationException {
        // Determine whether to add the Route data under routers or ports.
        // Router routes and logical port routes should also be added to the
        // routing table.
        List<String> ret = new ArrayList<String>();
        if (rtConfig.nextHop.toPort()) {
            ret.add(paths.getPortRoutePath(rtConfig.nextHopPort, id));
            if (portConfig == null) {
                PortZkManager portZkManager = new PortZkManager(zk, paths,
                    serializer);
                portConfig = portZkManager.get(
                    rtConfig.nextHopPort, PortDirectory.RouterPortConfig.class);
            }
            if(portConfig.isInterior()){
                ret.add(getRouteInRoutingTable(rtConfig));
            }
        } else {
            ret.add(paths.getRouterRoutePath(rtConfig.routerId, id));
            // Add the route to the routing table.
            ret.add(getRouteInRoutingTable(rtConfig));
        }
        return ret;
    }

    public String getRouteInRoutingTable(Route rt)
            throws StateAccessException, SerializationException {
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
            throws StateAccessException, SerializationException {
        return prepareRouteCreate(id, config, persistent, null);
    }

    /**
     *
     * @param id         The UUID of the new route.
     * @param rtConfig   The new route to be added.
     * @param persistent Should the route be deleted when this Midolman fails.
     * @param portConfig A LOCAL route is created at the same time as the port.
     *                   Therefore there's no port config in ZK. However, the
     *                   portConfig is available because of the create op, and
     *                   can be used to determine the type of port.
     * @return           The list of operations to install the route in all the
     *                   right places.
     * @throws StateAccessException
     */
    public List<Op> prepareRouteCreate(
            UUID id, Route rtConfig, boolean persistent,
            PortDirectory.RouterPortConfig portConfig)
            throws StateAccessException, SerializationException {
        CreateMode mode = persistent ? CreateMode.PERSISTENT
            : CreateMode.EPHEMERAL;
        // TODO(pino): sanity checking on route - egress belongs to device.
        List<Op> ops = new ArrayList<Op>();
        // Add to root
        ops.add(Op.create(paths.getRoutePath(id),
                serializer.serialize(rtConfig),
                Ids.OPEN_ACL_UNSAFE,
                mode));

        // Add under port or router. Router routes and logical port routes
        // should also be added to the routing table.
        for (String path :
                getSubDirectoryRoutePaths(id, rtConfig, portConfig)) {
            ops.add(Op.create(path, null, Ids.OPEN_ACL_UNSAFE, mode));
        }
        return ops;
    }

    private Set<String> getRoutingTablePaths(UUID portId)
            throws StateAccessException, SerializationException {
        Set<String> routePaths = new HashSet<String>();
        String portRoutesPath = paths.getPortRoutesPath(portId);
        Set<String> routeIds = zk.getChildren(portRoutesPath);
        for (String routeId : routeIds) {
            Route route = get(UUID.fromString(routeId));
            String routeKey = getRouteInRoutingTable(route);

            // Assume only persistent routes are processed
            routePaths.add(routeKey);
        }

        return routePaths;
    }

    /***
     * Copy all the routes in port route directory to the router's routing
     * table.
     *
     * @param portId  Port ID of the routes
     * @return  List of Op objects to perform the action
     * @throws StateAccessException
     */
    public List<Op> prepareCreatePortRoutesInTable(UUID portId)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<Op>();
        Set<String> routePaths = getRoutingTablePaths(portId);
        for (String routePath : routePaths) {
            // Assume only persistent routes are processed
            ops.add(Op.create(routePath, null, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        return ops;
    }

    /***
     * Delete all the routes in port route directory from the router's routing
     * table.
     *
     * @param portId  Port ID of the routes
     * @return  List of Op objects to perform the action
     * @throws StateAccessException
     */
    public List<Op> prepareDeletePortRoutesFromTable(UUID portId)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<Op>();
        Set<String> routePaths = getRoutingTablePaths(portId);
        for (String routePath : routePaths) {
            ops.add(Op.delete(routePath, -1));
        }

        return ops;
    }

    public List<Op> prepareLocalRoutesCreate(UUID portId,
            PortDirectory.RouterPortConfig config) throws StateAccessException,
            SerializationException {
        UUID routeId = UUID.randomUUID();
        Route route = new Route(0, 0, config.portAddr, 32, Route.NextHop.LOCAL,
                                portId, Route.NO_GATEWAY, 0, null, config.device_id);
        return prepareRouteCreate(routeId, route, true, config);
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
    public List<Op> prepareRouteDelete(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        String routePath = paths.getRoutePath(id);
        log.debug("Preparing to delete: " + routePath);
        ops.add(Op.delete(routePath, -1));
        Route config = get(id);
        for (String path : getSubDirectoryRoutePaths(id, config, null)) {
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
        }
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a route deletion,
     * with the assumption that this is an unlinked port route.
     *
     * @param id Route ID
     * @return A list of Op objects representing the operations to perform.
     * @throws StateAccessException
     *             Serialization or data access error occurred.
     */
    public List<Op> prepareUnlinkedPortRouteDelete(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        Route config = get(id);
        String routePath = paths.getRoutePath(id);
        String portRoutePath
                    = paths.getPortRoutePath(config.nextHopPort, id);
        ops.add(Op.delete(routePath, -1));
        ops.add(Op.delete(portRoutePath, -1));
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
            throws StateAccessException, SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareRouteCreate(id, route, persistent));
        return id;
    }

    public UUID create(Route route) throws StateAccessException,
            SerializationException {
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
    public Route get(UUID id) throws StateAccessException,
            SerializationException {
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
    public Route get(UUID id, Runnable watcher) throws StateAccessException,
            SerializationException {
        byte[] routeData = zk.get(paths.getRoutePath(id), watcher);
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
        Set<String> routeIds = zk.getChildren(
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
        Set<String> routeIds = zk.getChildren(
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
    public List<UUID> list(UUID routerId) throws StateAccessException,
            SerializationException {
        List<UUID> routes = listRouterRoutes(routerId, null);
        Set<String> portIds = zk.getChildren(
                paths.getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
            // For each MaterializedRouterPort, process it. Needs optimization.
            UUID portUUID = UUID.fromString(portId);
            byte[] data = zk.get(paths.getPortPath(portUUID), null);
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
    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareRouteDelete(id));
    }

    public boolean exists(UUID id) throws StateAccessException {

        String path = paths.getRoutePath(id);
        return zk.exists(path);
    }
}
