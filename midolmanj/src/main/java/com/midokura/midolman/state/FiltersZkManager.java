/*
 * Copyright 2012 Midokura Pte. Ltd.
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
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to manage the ZK data for the implicit filters of Ports, Bridges,
 * and Routers.
 */
public class FiltersZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(FiltersZkManager.class);

    /**
     * Initializes a FilterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public FiltersZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }


    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new filter (of a port, router, or bridge).
     *
     * @param id
     *            The id of a router, bridge or port.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareCreate(UUID id)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getFilterPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getFilterSnatBlocksPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a filter deletion.
     *
     * @param id
     *            ID of port, bridge or router whose filter is to be deleted.
     * @return A list of Op objects representing the operations to perform.
     * @throws com.midokura.midolman.state.StateAccessException
     */
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String basePath = pathManager.getBasePath();

        // Delete SNAT blocks
        String snatBlocksPath = pathManager.getFilterSnatBlocksPath(id);
        for (String snatBlock : getChildren(snatBlocksPath, null)) {
            String path = pathManager.getFilterSnatBlocksPath(id) + "/"
                 + snatBlock;
            log.debug("Preparing to delete: " + path);
            ops.add(Op.delete(path, -1));
        }
        log.debug("Preparing to delete: " + snatBlocksPath);
        ops.add(Op.delete(snatBlocksPath, -1));

        String filterPath = pathManager.getFilterPath(id);
        log.debug("Preparing to delete: " + filterPath);
        ops.add(Op.delete(filterPath, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new filter entry.
     *
     * @return The UUID of the newly created object.
     * @throws com.midokura.midolman.state.StateAccessException
     */
    public void create(UUID id) throws StateAccessException {
        multi(prepareCreate(id));
    }

    /***
     * Deletes a filter and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the filter state to delete.
     * @throws com.midokura.midolman.state.StateAccessException
     */
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    public NavigableSet<Integer> getSnatBlocks(UUID parentId, int ip)
            throws KeeperException, InterruptedException {
        StringBuilder sb = new StringBuilder(pathManager
                .getFilterSnatBlocksPath(parentId));
        sb.append("/").append(Integer.toHexString(ip));
        TreeSet<Integer> ports = new TreeSet<Integer>();
        Set<String> blocks = null;
        try {
            blocks = zk.getChildren(sb.toString(), null);
        } catch (NoNodeException e) {
            return ports;
        }
        for (String str : blocks)
            ports.add(Integer.parseInt(str));
        return ports;
    }

    public void addSnatReservation(UUID parentId, int ip, int startPort)
            throws StateAccessException {
        StringBuilder sb = new StringBuilder(pathManager
                .getFilterSnatBlocksPath(parentId));
        sb.append("/").append(Integer.toHexString(ip));

        // Call the safe add method to avoid exception when node exists.
        addPersistent_safe(sb.toString(), null);

        sb.append("/").append(startPort);
        addEphemeral(sb.toString(), null);
    }
}
