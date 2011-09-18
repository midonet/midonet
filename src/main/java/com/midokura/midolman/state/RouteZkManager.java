/*
 * @(#)RouteZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

/**
 * Class to manage the routing ZooKeeper data.
 * 
 * @version 1.6 10 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteZkManager extends ZkManager {

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

    public RouteZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    private String getSubDirectoryRoutePath(ZkNodeEntry<UUID, Route> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Determine whether to add the Route data under routers or ports.
        if (entry.value.nextHop == Route.NextHop.PORT) {
            // Check what kind of port this is.
            PortZkManager portManager = new PortZkManager(zk, pathManager
                    .getBasePath());
            ZkNodeEntry<UUID, PortConfig> port = portManager
                    .get(entry.value.nextHopPort);
            if (!(port.value instanceof RouterPortConfig)) {
                // Cannot add route to bridge ports
                throw new IllegalArgumentException(
                        "Can only add a route to a router");
            }
            return pathManager.getPortRoutePath(entry.value.nextHopPort,
                    entry.key);
        } else {
            return pathManager.getRouterRoutePath(entry.value.routerId,
                    entry.key);
        }
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new route.
     * 
     * @param entry
     *            ZooKeeper node representing a key-value entry of Route UUID
     *            and Route object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRouteCreate(ZkNodeEntry<UUID, Route> entry)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {

        List<Op> ops = new ArrayList<Op>();

        // Add to root
        try {
            ops.add(Op.create(pathManager.getRoutePath(entry.key),
                    serialize(entry.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize Route data", e, Route.class);
        }

        // Add under port or router
        ops.add(Op.create(getSubDirectoryRoutePath(entry), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new route entry.
     * 
     * @param route
     *            Route object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public UUID create(Route route) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, Route> entry = new ZkNodeEntry<UUID, Route>(id, route);
        zk.multi(prepareRouteCreate(entry));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a route with the given ID.
     * 
     * @param id
     *            The ID of the route.
     * @return Route object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, Route> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        return get(id, null);
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
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, Route> get(UUID id, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] routeData = zk.get(pathManager.getRoutePath(id), watcher);
        Route r = null;
        try {
            r = deserialize(routeData, Route.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize route " + id + " to Route", e,
                    Route.class);
        }
        return new ZkNodeEntry<UUID, Route>(id, r);
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
     * @return A list of ZooKeeper route nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, Route>> listRouterRoutes(UUID routerId,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> result = new ArrayList<ZkNodeEntry<UUID, Route>>();
        Set<String> routeIds = zk.getChildren(pathManager
                .getRouterRoutesPath(routerId), watcher);
        for (String routeId : routeIds) {
            result.add(get(UUID.fromString(routeId)));
        }
        return result;
    }

    /**
     * Gets a list of ZooKeeper route nodes belonging under the port directory
     * with the given ID.
     * 
     * @param portId
     *            The ID of the port to find the routes of.
     * @param watcher
     *            The watcher to set on the changes to the routes for this port.
     * @return A list of ZooKeeper route nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, Route>> listPortRoutes(UUID portId,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> result = new ArrayList<ZkNodeEntry<UUID, Route>>();
        Set<String> routeIds = zk.getChildren(pathManager
                .getPortRoutesPath(portId), watcher);
        for (String routeId : routeIds) {
            result.add(get(UUID.fromString(routeId)));
        }
        return result;
    }

    /**
     * Gets a list of ZooKeeper route nodes belonging to a router with the
     * given ID.
     * 
     * @param portId
     *            The ID of the router to find the routes of.
     * @return A list of ZooKeeper route nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, Route>> list(UUID routerId)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> routes = listRouterRoutes(routerId, null);
        Set<String> portIds = zk.getChildren(pathManager
                .getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
            // For each MaterializedRouterPort, process it. Needs optimization.
            UUID portUUID = UUID.fromString(portId);
            byte[] data = zk.get(pathManager.getPortPath(portUUID), null);
            PortConfig port = null;
            try {
                port = deserialize(data, PortConfig.class);
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not deserialize port " + portUUID
                                + " to PortConfig", e, PortConfig.class);
            }
            if (!(port instanceof RouterPortConfig)) {
                continue;
            }

            List<ZkNodeEntry<UUID, Route>> portRoutes = listPortRoutes(
                    portUUID, null);
            routes.addAll(portRoutes);
        }
        return routes;
    }

    /**
     * Constructs a list of operations to perform in a route deletion.
     * 
     * @param entry
     *            Route ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRouteDelete(ZkNodeEntry<UUID, Route> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getRoutePath(entry.key), -1));
        if (entry.value.nextHop == Route.NextHop.PORT) {
            ops.add(Op.delete(pathManager.getPortRoutePath(
                    entry.value.nextHopPort, entry.key), -1));
        } else {
            ops.add(Op.delete(pathManager.getRouterRoutePath(
                    entry.value.routerId, entry.key), -1));
        }
        return ops;
    }

    /***
     * Deletes a route and its related data from the ZooKeeper directories
     * atomically.
     * 
     * @param id
     *            ID of the route to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void delete(UUID id) throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        this.zk.multi(prepareRouteDelete(get(id)));
    }

}
