/*
 * @(#)RouteZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 10 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouteZkManager extends ZkManager {

    public static class RouteRefConfig implements Serializable {

        private static final long serialVersionUID = 1L;
        public String path = null;

        public RouteRefConfig() {
        }

        public RouteRefConfig(String path) {
            this.path = path;
        }
    }

    /**
     * Constructor to set ZooKeeper and basepath.
     * 
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public RouteZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    public List<Op> prepareRouteCreate(ZkNodeEntry<UUID, Route> entry)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {

        List<Op> ops = new ArrayList<Op>();
        ZkNodeEntry<UUID, PortConfig> port = null;
        String path = null;
        if (entry.value.nextHop == Route.NextHop.PORT) {
            // Check what kind of port this is.
            PortZkManager portManager = new PortZkManager(zk, pathManager
                    .getBasePath());
            port = portManager.get(entry.value.nextHopPort);
            if (!(port.value instanceof RouterPortConfig)) {
                // Cannot add route to bridge ports
                throw new IllegalArgumentException(
                        "Can only add a route to a router");
            }
            path = pathManager.getPortRoutePath(entry.value.nextHopPort,
                    entry.key);
        } else {
            path = pathManager.getRouterRoutePath(entry.value.routerId,
                    entry.key);
        }

        try {
            ops.add(Op.create(path, serialize(entry.value),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize Route data", e, RouteRefConfig.class);
        }
        try {
            // Add reference to this
            ops.add(Op.create(pathManager.getRoutePath(entry.key),
                    serialize(new RouteRefConfig(path)), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouteRefConfig data", e,
                    RouteRefConfig.class);
        }
        return ops;
    }

    public UUID create(Route route) throws InterruptedException,
            KeeperException, IOException, ClassNotFoundException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, Route> entry = new ZkNodeEntry<UUID, Route>(id, route);
        zk.multi(prepareRouteCreate(entry));
        return id;
    }

    public RouteRefConfig getRouteRefConfig(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.getData(pathManager.getRoutePath(id), null, null);
        try {
            return deserialize(data, RouteRefConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize route ref " + id
                            + " to RouteRefConfig", e, RouteRefConfig.class);
        }
    }

    public ZkNodeEntry<UUID, Route> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        RouteRefConfig routeRef = getRouteRefConfig(id);
        byte[] routeData = zk.getData(routeRef.path, null, null);
        Route r = null;
        try {
            r = deserialize(routeData, Route.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize port " + id + " to PortConfig", e,
                    PortConfig.class);
        }
        return new ZkNodeEntry<UUID, Route>(id, r);

    }

    public List<ZkNodeEntry<UUID, Route>> listRouterRoutes(UUID routerId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> result = new ArrayList<ZkNodeEntry<UUID, Route>>();
        List<String> routeIds = zk.getChildren(pathManager
                .getRouterRoutesPath(routerId), null);
        for (String routeId : routeIds) {
            UUID id = UUID.fromString(routeId);
            byte[] data = zk.getData(pathManager.getRouterRoutePath(routerId,
                    id), null, null);
            try {
                result.add(new ZkNodeEntry<UUID, Route>(id, deserialize(data,
                        Route.class)));
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not deserialize route " + id + " to Route", e,
                        Route.class);
            }
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, Route>> listPortRoutes(UUID portId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> result = new ArrayList<ZkNodeEntry<UUID, Route>>();
        List<String> routeIds = zk.getChildren(pathManager
                .getPortRoutesPath(portId), null);
        for (String routeId : routeIds) {
            UUID id = UUID.fromString(routeId);
            byte[] data = zk.getData(pathManager.getPortRoutePath(portId, id),
                    null, null);
            try {
                result.add(new ZkNodeEntry<UUID, Route>(id, deserialize(data,
                        Route.class)));
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                        "Could not deserialize route " + id + " to Route", e,
                        Route.class);
            }
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, Route>> list(UUID routerId)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Route>> routes = listRouterRoutes(routerId);
        List<String> portIds = zk.getChildren(pathManager
                .getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
            // For each MaterializedRouterPort, process it.
            UUID portUUID = UUID.fromString(portId);
            byte[] data = zk.getData(pathManager.getPortPath(portUUID), null,
                    null);
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

            List<ZkNodeEntry<UUID, Route>> portRoutes = listPortRoutes(portUUID);
            routes.addAll(portRoutes);
        }
        return routes;
    }

    public List<Op> getRouterRouteDeleteOps(UUID id, UUID routerId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getRouterRoutePath(routerId, id), -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }

    public List<Op> getPortRouteDeleteOps(UUID id, UUID portId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getPortRoutePath(portId, id), -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }

    public List<Op> getDeleteOps(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        RouteRefConfig routeRef = getRouteRefConfig(id);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(routeRef.path, -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            IOException, ZkStateSerializationException {
        this.zk.multi(getDeleteOps(id));
    }

}
