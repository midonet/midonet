/*
 * @(#)RouterZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

/**
 * Class to manage the router ZooKeeper data.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(RouterZkManager.class);

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

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new router.
     * 
     * @param routerNode
     *            ZooKeeper node representing a key-value entry of router UUID
     *            and RouterConfig object.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareRouterCreate(UUID id)
            throws ZkStateSerializationException {
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
     * @throws StateAccessException
     */
    public List<Op> prepareRouterDelete(UUID id)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String basePath = pathManager.getBasePath();
        ChainZkManager chainZkManager = new ChainZkManager(zk, basePath);
        RouteZkManager routeZkManager = new RouteZkManager(zk, basePath);
        PortZkManager portZkManager = new PortZkManager(zk, basePath);

        // Delete SNAT blocks
        Set<String> snatBlocks = getChildren(pathManager
                .getRouterSnatBlocksPath(id), null);
        for (String snatBlock : snatBlocks) {
            String path = pathManager.getRouterSnatBlocksPath(id) + "/"
                    + snatBlock;
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
        }
        String snatBlockPath = pathManager.getRouterSnatBlocksPath(id);
        log.debug("Preparing to delete: " + snatBlockPath);
        ops.add(Op.delete(snatBlockPath, -1));

        // Delete routing table
        String routingTablePath = pathManager.getRouterRoutingTablePath(id);
        Set<String> tableEntries = getChildren(routingTablePath, null);
        for (String tableEntry : tableEntries) {
            String path = pathManager.getRouterRoutingTablePath(id) + "/"
                    + tableEntry;
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
        }
        log.debug("Preparing to delete: " + routingTablePath);
        ops.add(Op.delete(routingTablePath, -1));

        // Get chains delete ops.
        List<ZkNodeEntry<UUID, ChainConfig>> entries = chainZkManager.list(id);
        for (ZkNodeEntry<UUID, ChainConfig> entry : entries) {
            ops.addAll(chainZkManager.prepareChainDelete(entry));
        }
        String chainsPath = pathManager.getRouterChainsPath(id);
        log.debug("Preparing to delete: " + chainsPath);
        ops.add(Op.delete(chainsPath, -1));

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

        String routerPath = pathManager.getRouterPath(id);
        log.debug("Preparing to delete: " + routerPath);
        ops.add(Op.delete(routerPath, -1));
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
     * @throws StateAccessException
     */
    public UUID create() throws ZkStateSerializationException,
            StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareRouterCreate(id));
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
            throws StateAccessException {
        StringBuilder sb = new StringBuilder(pathManager
                .getRouterSnatBlocksPath(routerId));
        sb.append("/").append(Integer.toHexString(ip));

        // Call the safe add method to avoid exception when node exists.
        addPersistent_safe(sb.toString(), null);

        sb.append("/").append(startPort);
        addEphemeral(sb.toString(), null);
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws StateAccessException {
        return getSubDirectory(pathManager.getRouterRoutingTablePath(routerId));
    }
}
