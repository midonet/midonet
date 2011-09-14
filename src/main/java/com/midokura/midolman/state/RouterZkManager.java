/*
 * @(#)RouterZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterZkManager extends ZkManager {

    /**
     * RouterZkManager constructor.
     * 
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public RouterZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

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

    public UUID create(RouterConfig router) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, RouterConfig> routerNode = new ZkNodeEntry<UUID, RouterConfig>(
                id, router);
        zk.multi(prepareRouterCreate(routerNode));
        return id;
    }

    public ZkNodeEntry<UUID, RouterConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.getData(pathManager.getRouterPath(id), null, null);
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

    public List<ZkNodeEntry<UUID, RouterConfig>> list(UUID tenantId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, RouterConfig>> result = new ArrayList<ZkNodeEntry<UUID, RouterConfig>>();
        List<String> routerIds = zk.getChildren(pathManager
                .getTenantRoutersPath(tenantId), null);
        for (String routerId : routerIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(routerId)));
        }
        return result;
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

    public List<Op> getDeleteOps(UUID id, UUID tenantId)
            throws KeeperException, InterruptedException, IOException,
            ClassNotFoundException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        // Get rhains delete ops.
        ChainZkManager chainZk = new ChainZkManager(zk, basePath);
        HashMap<UUID, ChainConfig> chains = chainZk.list(id);
        for (Map.Entry<UUID, ChainConfig> entry : chains.entrySet()) {
            ops.addAll(chainZk.getDeleteOps(entry.getKey(), id));
        }
        // Get routes delete ops.
        RouteZkManager routeZk = new RouteZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, Route>> routes = routeZk.listRouterRoutes(id);
        for (ZkNodeEntry<UUID, Route> entry : routes) {
            ops.addAll(routeZk.getRouterRouteDeleteOps(entry.key, id));
        }
        // Get ports delete ops
        PortZkManager portZk = new PortZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, PortConfig>> ports = portZk.listRouterPorts(id);
        for (ZkNodeEntry<UUID, PortConfig> entry : ports) {
            ops.addAll(portZk.prepareRouterPortDelete(entry));
        }
        ops.add(Op.delete(pathManager.getTenantRouterPath(tenantId, id), -1));
        ops.add(Op.delete(pathManager.getRouterPath(id), -1));
        return ops;
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            IOException, ClassNotFoundException, ZkStateSerializationException {
        ZkNodeEntry<UUID, RouterConfig> router = get(id);
        delete(id, router.value.tenantId);
    }

    public void delete(UUID id, UUID tenantId) throws InterruptedException,
            KeeperException, IOException, ClassNotFoundException,
            ZkStateSerializationException {
        this.zk.multi(getDeleteOps(id, tenantId));
    }
}
