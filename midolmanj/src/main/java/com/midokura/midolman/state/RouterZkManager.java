/*
 * @(#)RouterZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;

/**
 * Class to manage the router ZooKeeper data.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(RouterZkManager.class);

    public static class RouterConfig {

        public UUID inboundFilter;
        public UUID outboundFilter;

        public RouterConfig() {
            super();
        }

        public RouterConfig(UUID inboundFilter, UUID outboundFilter) {
            this.inboundFilter = inboundFilter;
            this.outboundFilter = outboundFilter;
        }
    }

    RouteZkManager routeZkManager;
    FiltersZkManager filterZkManager;
    PortZkManager portZkManager;

    /**
     * Initializes a RouterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public RouterZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        routeZkManager = new RouteZkManager(zk, basePath);
        filterZkManager = new FiltersZkManager(zk, basePath);
        portZkManager = new PortZkManager(zk, basePath);

    }

    public List<Op> prepareRouterCreate(UUID id, RouterConfig config)
            throws ZkStateSerializationException {
        return prepareRouterCreate(
                new ZkNodeEntry<UUID, RouterConfig>(id, config));
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new router.
     *
     * @param routerNode
     *            ZooKeeper node representing a key-value entry of router UUID
     *            and RouterConfig object.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareRouterCreate(
            ZkNodeEntry<UUID, RouterConfig> routerNode)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        UUID id = routerNode.key;
        RouterConfig config = routerNode.value;
        try {
            ops.add(Op.create(pathManager.getRouterPath(id), serialize(config),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterConfig", e, RouterConfig.class);
        }

        ops.add(Op.create(pathManager.getRouterPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutingTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterArpTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.addAll(filterZkManager.prepareCreate(id));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a router deletion.
     *
     * @param id
     *            The ID of a virtual router to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     */
    public List<Op> prepareRouterDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String basePath = pathManager.getBasePath();

        // Get routes delete ops.
        List<ZkNodeEntry<UUID, Route>> routes = routeZkManager
                .listRouterRoutes(id, null);
        for (ZkNodeEntry<UUID, Route> entry : routes) {
            ops.addAll(routeZkManager.prepareRouteDelete(entry));
        }
        String routesPath = pathManager.getRouterRoutesPath(id);
        log.debug("Preparing to delete: " + routesPath);
        ops.add(Op.delete(routesPath, -1));

        // Get ports delete ops
        List<ZkNodeEntry<UUID, PortConfig>> ports = portZkManager
                .listRouterPorts(id);
        for (ZkNodeEntry<UUID, PortConfig> entry : ports) {
            ops.addAll(portZkManager.preparePortDelete(entry));
        }
        String portsPath = pathManager.getRouterPortsPath(id);
        log.debug("Preparing to delete: " + portsPath);
        ops.add(Op.delete(portsPath, -1));

        // Delete routing table
        String routingTablePath = pathManager.getRouterRoutingTablePath(id);
        log.debug("Preparing to delete: " + routingTablePath);
        ops.add(Op.delete(routingTablePath, -1));

        // Delete ARP table
        String arpTablePath = pathManager.getRouterArpTablePath(id);
        log.debug("Preparing to delete: " + arpTablePath);
        ops.add(Op.delete(arpTablePath, -1));

        String routerPath = pathManager.getRouterPath(id);
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
        ops.addAll(filterZkManager.prepareDelete(id));
        return ops;
    }

    /**
      * Construct a list of ZK operations needed to update the configuration of
      * a router.
      *
     * @param id
     *          ID of the router to update
     * @param config
      *         the new router configuration.
      * @return
      *          The ZK operation required to update the router.
      * @throws ZkStateSerializationException if the RouterConfig could not be
      *          serialized.
      */
    public Op prepareUpdate(UUID id, RouterConfig config)
            throws ZkStateSerializationException {
        try {
            return Op.setData(
                    pathManager.getRouterPath(id), serialize(config), -1);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterConfig", e, RouterConfig.class);
        }
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new router entry.
     *
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     */
    public UUID create() throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareRouterCreate(new ZkNodeEntry<UUID, RouterConfig>(id,
                new RouterConfig())));
        return id;
    }

    /***
     * Deletes a router and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the router to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws StateAccessException
     */
    public void delete(UUID id) throws ZkStateSerializationException,
            StateAccessException {
        multi(prepareRouterDelete(id));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a router with the given ID.
     *
     * @param id The ID of the router.
     * @return A key-value pair whose key is the ID of the Router, and whose
     *         value is the configuration of the Router.
     * @throws StateAccessException if deserialization of the Router's config
     *                              failed, or if no Router with that ID could be found.
     */
    public ZkNodeEntry<UUID, RouterConfig> get(UUID id)
            throws StateAccessException {
        return get(id, null);
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a router with the given ID
     * and sets a watcher for changes to the router's configuration.
     *
     * @param id The ID of the router.
     * @return A key-value pair whose key is the ID of the Router, and whose
     *         value is the configuration of the Router.
     * @throws StateAccessException if deserialization of the Router's config
     *                              failed, or if no Router with that ID could be found.
     */
    public ZkNodeEntry<UUID, RouterConfig> get(UUID id, Runnable watcher)
            throws StateAccessException {
        byte[] data = get(pathManager.getRouterPath(id), watcher);
        RouterConfig config = null;
        try {
            config = deserialize(data, RouterConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize router " + id + " to RouterConfig",
                    e, RouterConfig.class);
        }
        return new ZkNodeEntry<UUID, RouterConfig>(id, config);
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws StateAccessException {
        return getSubDirectory(pathManager.getRouterRoutingTablePath(routerId));
    }

    public Directory getArpTableDirectory(UUID routerId)
            throws StateAccessException {
        return getSubDirectory(pathManager.getRouterArpTablePath(routerId));
    }
}
