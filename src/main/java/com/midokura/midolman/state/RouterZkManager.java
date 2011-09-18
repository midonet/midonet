/*
 * @(#)RouterZkManager        1.6 11/09/08
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
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.RouterDirectory.RouterConfig;

/**
 * Class to manage the router ZooKeeper data.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterZkManager extends ZkManager {

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
    }

    public RouterZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new router.
     * 
     * @param routerNode
     *            ZooKeeper node representing a key-value entry of router UUID
     *            and RouterConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRouterCreate(
            ZkNodeEntry<UUID, RouterConfig> routerNode)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getRouterPath(routerNode.key),
                    serialize(routerNode.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterConfig", e, RouterConfig.class);
        }
        ops.add(Op.create(pathManager.getTenantRouterPath(
                routerNode.value.tenantId, routerNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterPortsPath(routerNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutesPath(routerNode.key),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterChainsPath(routerNode.key),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // ops.add(Op.create(pathManager.getRouterRoutingTablePath(id), null,
        // Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        // ops.add(Op.create(pathManager.getRouterSnatBlocksPath(id), null,
        // Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new router entry.
     * 
     * @param router
     *            Router object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public UUID create(RouterConfig router) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, RouterConfig> routerNode = new ZkNodeEntry<UUID, RouterConfig>(
                id, router);
        zk.multi(prepareRouterCreate(routerNode));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a router with the given ID.
     * 
     * @param id
     *            The ID of the router.
     * @return RouterConfig object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, RouterConfig> get(UUID id, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = zk.get(pathManager.getRouterPath(id), watcher);
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

    /**
     * Gets a ZooKeeper node entry key-value pair of a router with the given ID
     * and sets a watcher on the node.
     * 
     * @param id
     *            The ID of the router.
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
    public ZkNodeEntry<UUID, RouterConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        return get(id, null);
    }

    /**
     * Gets a list of ZooKeeper router nodes belonging to a tenant with the
     * given ID.
     * 
     * @param tenantId
     *            The ID of the tenant to find the routers of.
     * @param watcher
     *            The watcher to set on the changes to the routers for this
     *            router.
     * @return A list of ZooKeeper router nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, RouterConfig>> list(UUID tenantId,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, RouterConfig>> result = new ArrayList<ZkNodeEntry<UUID, RouterConfig>>();
        Set<String> routerIds = zk.getChildren(pathManager
                .getTenantRoutersPath(tenantId), watcher);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
    }

    /**
     * Gets a list of ZooKeeper router nodes belonging to a tenant with the
     * given ID.
     * 
     * @param tenantId
     *            The ID of the tenant to find the routers of.
     * @return A list of ZooKeeper router nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, RouterConfig>> list(UUID tenantId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return list(tenantId, null);
    }

    /**
     * Updates the BridgeConfig values with the given BridgeConfig object.
     * 
     * @param entry
     *            BridgeConfig object to save.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void update(ZkNodeEntry<UUID, RouterConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk.update(pathManager.getRouterPath(entry.key),
                    serialize(entry.value));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize router " + entry.key
                            + " to RouterConfig", e, RouterConfig.class);
        }
    }

    /**
     * Constructs a list of operations to perform in a router deletion.
     * 
     * @param entry
     *            Router ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRouterDelete(
            ZkNodeEntry<UUID, RouterConfig> routerNode) throws KeeperException,
            InterruptedException, ClassNotFoundException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ChainZkManager chainZkManager = new ChainZkManager(zk, basePath);
        RouteZkManager routeZkManager = new RouteZkManager(zk, basePath);
        PortZkManager portZkManager = new PortZkManager(zk, basePath);
        // Get rhains delete ops.
        List<ZkNodeEntry<UUID, ChainConfig>> entries = chainZkManager
                .list(routerNode.key);
        for (ZkNodeEntry<UUID, ChainConfig> entry : entries) {
            ops.addAll(chainZkManager.prepareChainDelete(entry));
        }
        // Get routes delete ops.
        List<ZkNodeEntry<UUID, Route>> routes = routeZkManager
                .listRouterRoutes(routerNode.key, null);
        for (ZkNodeEntry<UUID, Route> entry : routes) {
            ops.addAll(routeZkManager.prepareRouteDelete(entry));
        }
        // Get ports delete ops
        List<ZkNodeEntry<UUID, PortConfig>> ports = portZkManager
                .listRouterPorts(routerNode.key);
        for (ZkNodeEntry<UUID, PortConfig> entry : ports) {
            ops.addAll(portZkManager.prepareRouterPortDelete(entry));
        }
        ops.add(Op.delete(pathManager.getTenantRouterPath(
                routerNode.value.tenantId, routerNode.key), -1));
        ops.add(Op.delete(pathManager.getRouterPath(routerNode.key), -1));
        return ops;
    }

    /***
     * Deletes a router and its related data from the ZooKeeper directories
     * atomically.
     * 
     * @param id
     *            ID of the router to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void delete(UUID id) throws InterruptedException, KeeperException,
            IOException, ClassNotFoundException, ZkStateSerializationException {
        this.zk.multi(prepareRouterDelete(get(id)));
    }
}
