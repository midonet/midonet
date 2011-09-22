/*
 * @(#)RouterZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;

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
    public List<Op> prepareRouterCreate(UUID id)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getRouterPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterChainsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterSnatBlocksPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterRoutingTablePath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
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
    public List<Op> prepareRouterDelete(UUID id) throws KeeperException,
            InterruptedException, ClassNotFoundException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        String basePath = pathManager.getBasePath();
        ChainZkManager chainZkManager = new ChainZkManager(zk, basePath);
        RouteZkManager routeZkManager = new RouteZkManager(zk, basePath);
        PortZkManager portZkManager = new PortZkManager(zk, basePath);

        // Delete SNAT blocks
        Set<String> snatBlocks = zk.getChildren(pathManager
                .getRouterSnatBlocksPath(id), null);
        for (String snatBlock : snatBlocks) {
            ops.add(Op.delete(pathManager.getRouterSnatBlocksPath(id) + "/"
                    + snatBlock, -1));
        }
        ops.add(Op.delete(pathManager.getRouterSnatBlocksPath(id), -1));

        // Delete routing table
        Set<String> tableEntries = zk.getChildren(pathManager
                .getRouterRoutingTablePath(id), null);
        for (String tableEntry : tableEntries) {
            ops.add(Op.delete(pathManager.getRouterRoutingTablePath(id) + "/"
                    + tableEntry, -1));
        }
        ops.add(Op.delete(pathManager.getRouterRoutingTablePath(id), -1));

        // Get chains delete ops.
        List<ZkNodeEntry<UUID, ChainConfig>> entries = chainZkManager.list(id);
        for (ZkNodeEntry<UUID, ChainConfig> entry : entries) {
            ops.addAll(chainZkManager.prepareChainDelete(entry));
        }
        ops.add(Op.delete(pathManager.getRouterChainsPath(id), -1));
        // Get routes delete ops.
        List<ZkNodeEntry<UUID, Route>> routes = routeZkManager
                .listRouterRoutes(id, null);
        for (ZkNodeEntry<UUID, Route> entry : routes) {
            ops.addAll(routeZkManager.prepareRouteDelete(entry));
        }
        ops.add(Op.delete(pathManager.getRouterRoutesPath(id), -1));
        // Get ports delete ops
        List<ZkNodeEntry<UUID, PortConfig>> ports = portZkManager
                .listRouterPorts(id);
        for (ZkNodeEntry<UUID, PortConfig> entry : ports) {
            ops.addAll(portZkManager.preparePortDelete(entry));
        }
        ops.add(Op.delete(pathManager.getRouterPortsPath(id), -1));

        ops.add(Op.delete(pathManager.getRouterPath(id), -1));
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
    public UUID create() throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareRouterCreate(id));
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
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void delete(UUID id) throws InterruptedException, KeeperException,
            IOException, ClassNotFoundException, ZkStateSerializationException {
        this.zk.multi(prepareRouterDelete(id));
    }

    public NavigableSet<Short> getSnatBlocks(UUID routerId, int ip)
            throws KeeperException, InterruptedException {
        StringBuilder sb = new StringBuilder(pathManager
                .getRouterSnatBlocksPath(routerId));
        sb.append("/").append(Integer.toHexString(ip));
        TreeSet<Short> ports = new TreeSet<Short>();
        Set<String> blocks = null;
        try {
            blocks = zk.getChildren(sb.toString(), null);
        } catch (NoNodeException e) {
            return ports;
        }
        for (String str : blocks)
            ports.add((short) Integer.parseInt(str));
        return ports;
    }

    public void addSnatReservation(UUID routerId, int ip, short startPort)
            throws KeeperException, InterruptedException {
        StringBuilder sb = new StringBuilder(pathManager
                .getRouterSnatBlocksPath(routerId));
        sb.append("/").append(Integer.toHexString(ip));
        try {
            zk.add(sb.toString(), null, CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
        }
        sb.append("/").append(startPort);
        zk.add(sb.toString(), null, CreateMode.EPHEMERAL);
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws KeeperException {
        return zk.getSubDirectory(pathManager
                .getRouterRoutingTablePath(routerId));
    }
}
