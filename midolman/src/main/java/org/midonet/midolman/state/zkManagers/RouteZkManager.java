/*
 * Copyright (c) 2011-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Function;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
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

import javax.annotation.Nullable;

/**
 * Class to manage the routing ZooKeeper data.
 */
public class RouteZkManager extends AbstractZkManager<UUID, Route> {

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

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getRoutePath(id);
    }

    @Override
    protected Class<Route> getConfigClass() {
        return Route.class;
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
    private List<String> getSubDirectoryRoutePaths(UUID id, Route rtConfig,
                                                   PortDirectory.RouterPortConfig portConfig)
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
    public List<Op> prepareRouteCreate(UUID id, Route rtConfig, boolean persistent,
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

    public UUID preparePersistPortRouteCreate (
            List<Op> ops, UUID routerId, IPv4Subnet src,  IPv4Subnet dst,
            PortDirectory.RouterPortConfig cfg, IPv4Addr gwIp)
            throws SerializationException, StateAccessException {
        return preparePersistPortRouteCreate(ops, routerId, src, dst, cfg, gwIp,
            Route.DEFAULT_WEIGHT);
    }

    public UUID preparePersistPortRouteCreate(
            List<Op> ops, UUID routerId, IPv4Subnet src,  IPv4Subnet dst,
            PortDirectory.RouterPortConfig cfg, IPv4Addr gwIp, int weight)
            throws SerializationException, StateAccessException {
        UUID id = UUID.randomUUID();
        Route rt = Route.nextHopPortRoute(src, dst, cfg.id, gwIp, weight, routerId);
        ops.addAll(prepareRouteCreate(id, rt, true, cfg));
        return id;
    }

    public UUID preparePersistDefaultRouteCreate(List<Op> ops, UUID routerId,
                                                 PortDirectory.RouterPortConfig cfg)
            throws SerializationException, StateAccessException {
        return preparePersistDefaultRouteCreate(ops, routerId, cfg,
            Route.DEFAULT_WEIGHT);
    }

    public UUID preparePersistDefaultRouteCreate(List<Op> ops, UUID routerId,
                                                 PortDirectory.RouterPortConfig cfg, int weight)
            throws SerializationException, StateAccessException {
        UUID id = UUID.randomUUID();
        Route rt = Route.defaultRoute(cfg.id, weight, routerId);
        ops.addAll(prepareRouteCreate(id, rt, true, cfg));
        return id;
    }

    public void preparePersistPortRouteCreate(
            List<Op> ops, UUID id, IPv4Subnet src, IPv4Subnet dest,
            UUID nextHopPortId, IPv4Addr nextHopAddr, UUID routerId,
            PortDirectory.RouterPortConfig rpCfg)
            throws SerializationException, StateAccessException {
        preparePersistPortRouteCreate(ops, id, src, dest, nextHopPortId,
                nextHopAddr, Route.DEFAULT_WEIGHT, routerId, rpCfg);
    }

    public void preparePersistPortRouteCreate(
            List<Op> ops, UUID id, IPv4Subnet src, IPv4Subnet dest,
            UUID nextHopPortId, IPv4Addr nextHopAddr, int weight,
            UUID routerId, PortDirectory.RouterPortConfig rpCfg)
            throws SerializationException, StateAccessException {

        Route r = Route.nextHopPortRoute(src, dest, nextHopPortId, nextHopAddr,
                weight, routerId);
        ops.addAll(prepareRouteCreate(id,r, true, rpCfg));
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

    /**
     * Set an Op to create one route entry in the routing table.  Since this
     * method does not assume that this route has been created, it is useful
     * when you want to use it as part of new port creation/link.
     * @throws StateAccessException
     */
    public void prepareCreatePortRouteInTable(List<Op> ops,
            UUID portId, PortDirectory.RouterPortConfig pCfg)
            throws SerializationException, StateAccessException {

        Route route = Route.localRoute(portId, pCfg.portAddr, pCfg.device_id);
        String routeKey = getRouteInRoutingTable(route);

        // Assume only persistent routes are processed
        ops.add(Op.create(routeKey, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
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

    public void prepareRoutesDelete(List<Op> ops, UUID routerId,
                                    final IPv4Subnet dstSub)
        throws SerializationException, StateAccessException {
        prepareRoutesDelete(ops, routerId, dstSub, -1);
    }

    public void prepareRoutesDelete(List<Op> ops, UUID routerId,
                                    final IPv4Subnet dstSub,
                                    final int gateway)
            throws SerializationException, StateAccessException {

        prepareRoutesDelete(ops, routerId,
                new Function<Route, Boolean>() {
                    @Override
                    public Boolean apply(@Nullable Route route) {
                        if (gateway == -1 && dstSub == null) {
                            return false;
                        }
                        if (gateway == -1) {
                            return route.hasDstSubnet(dstSub);
                        } else if (dstSub == null) {
                            return route.nextHopGateway == gateway;
                        } else {
                            return route.hasDstSubnet(dstSub) &&
                                (route.nextHopGateway == gateway);
                        }
                    }
                });
    }

    public void prepareRoutesDelete(List<Op> ops, UUID routerId,
                                    Function<Route, Boolean> matcher)
            throws StateAccessException, SerializationException {
        List<UUID> routeIds = list(routerId);
        for (UUID routeId : routeIds) {
            Route route = get(routeId);
            if (matcher.apply(route)) {
                ops.addAll(prepareRouteDelete(routeId));
            }
        }
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
        return getUuidList(paths.getRouterRoutesPath(routerId), watcher);
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
        return getUuidList(paths.getPortRoutesPath(portId), watcher);
    }

    public void listPortRoutesAsync(UUID portId,
                                    final DirectoryCallback<Set<UUID>> listPortRoutesCallback,
                                    Directory.TypedWatcher watcher){

        getUUIDSetAsync(paths.getPortRoutesPath(portId), listPortRoutesCallback, watcher);
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
}
