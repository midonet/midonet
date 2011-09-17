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
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public RouterZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
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

    public ZkNodeEntry<UUID, RouterConfig> get(UUID id, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        byte[] data = getData(pathManager.getRouterPath(id), watcher);
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

    public ZkNodeEntry<UUID, RouterConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        return get(id, null);
    }

    public List<ZkNodeEntry<UUID, RouterConfig>> list(UUID tenantId,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, RouterConfig>> result = new ArrayList<ZkNodeEntry<UUID, RouterConfig>>();
        List<String> routerIds = getChildren(pathManager
                .getTenantRoutersPath(tenantId), watcher);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, RouterConfig>> list(UUID tenantId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return list(tenantId, null);
    }

    public void update(ZkNodeEntry<UUID, RouterConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        // Update any version for now.
        try {
            zk.setData(pathManager.getRouterPath(entry.key),
                    serialize(entry.value), -1);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize router " + entry.key
                            + " to RouterConfig", e, RouterConfig.class);
        }
    }

    public List<Op> prepareRouterDelete(
            ZkNodeEntry<UUID, RouterConfig> routerNode) throws KeeperException,
            InterruptedException, ClassNotFoundException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        // Get rhains delete ops.
        ChainZkManager chainZk = new ChainZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, ChainConfig>> entries = chainZk
                .list(routerNode.key);
        for (ZkNodeEntry<UUID, ChainConfig> entry : entries) {
            ops.addAll(chainZk.getDeleteOps(entry.key, routerNode.key));
        }
        // Get routes delete ops.
        RouteZkManager routeZk = new RouteZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, Route>> routes = routeZk.listRouterRoutes(
                routerNode.key, null);
        for (ZkNodeEntry<UUID, Route> entry : routes) {
            ops.addAll(routeZk.prepareRouteDelete(entry));
        }
        // Get ports delete ops
        PortZkManager portZk = new PortZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, PortConfig>> ports = portZk
                .listRouterPorts(routerNode.key);
        for (ZkNodeEntry<UUID, PortConfig> entry : ports) {
            ops.addAll(portZk.prepareRouterPortDelete(entry));
        }
        ops.add(Op.delete(pathManager.getTenantRouterPath(
                routerNode.value.tenantId, routerNode.key), -1));
        ops.add(Op.delete(pathManager.getRouterPath(routerNode.key), -1));
        return ops;
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            IOException, ClassNotFoundException, ZkStateSerializationException {
        this.zk.multi(prepareRouterDelete(get(id)));
    }
}
