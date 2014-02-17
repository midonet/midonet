/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPAddr$;

import java.util.*;

/**
 * Class to manage the ZK data for the implicit filters of Ports, Bridges,
 * and Routers.
 */
public class FiltersZkManager extends AbstractZkManager {

    /**
     * Initializes a FilterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public FiltersZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    public FiltersZkManager(Directory dir, String basePath,
                            Serializer serializer) {
        this(new ZkManager(dir, basePath),
             new PathBuilder(basePath), serializer);
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
            throws SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getFilterPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getFilterSnatBlocksPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a filter deletion.
     *
     * @param id
     *            ID of port, bridge or router whose filter is to be deleted.
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        String basePath = paths.getBasePath();

        // The SNAT blocks are nested under
        // /filters/<deviceId>/snat_blocks/<ip>/<startPortRange>
        // Delete everything under snat_blocks
        String devicePath = paths.getFilterSnatBlocksPath(id);
        for (String ipStr : zk.getChildren(devicePath, null)) {
            IPAddr ipv4 = IPAddr$.MODULE$.fromString(ipStr);
            String ipPath =
                paths.getFilterSnatBlocksPath(id, ipv4);
            for (String portBlock : zk.getChildren(ipPath, null))
                ops.add(Op.delete(
                    paths.getFilterSnatBlocksPath(
                        id, ipv4, Integer.parseInt(portBlock)),
                    -1));
            ops.add(Op.delete(ipPath, -1));
        }
        ops.add(Op.delete(devicePath, -1));

        // Finally, delete the filter path for the device.
        String filterPath = paths.getFilterPath(id);
        ops.add(Op.delete(filterPath, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new filter entry.
     *
     * @return The UUID of the newly created object.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public void create(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareCreate(id));
    }

    /***
     * Deletes a filter and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the filter state to delete.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public void delete(UUID id) throws StateAccessException {
        zk.multi(prepareDelete(id));
    }

    public NavigableSet<Integer> getSnatBlocks(UUID parentId, IPAddr ip)
            throws StateAccessException {
        StringBuilder sb = new StringBuilder(paths
                .getFilterSnatBlocksPath(parentId));
        sb.append("/").append(ip.toString());
        TreeSet<Integer> ports = new TreeSet<Integer>();
        Set<String> blocks = null;
        try {
            blocks = zk.getChildren(sb.toString(), null);
        } catch (NoStatePathException e) {
            return ports;
        }
        for (String str : blocks)
            ports.add(Integer.parseInt(str));
        return ports;
    }

    public void addSnatReservation(UUID parentId, IPAddr ip, int startPort)
            throws StateAccessException {
        StringBuilder sb = new StringBuilder(paths
                .getFilterSnatBlocksPath(parentId));
        sb.append("/").append(ip.toString());

        // Call the safe add method to avoid exception when node exists.
        zk.addPersistent_safe(sb.toString(), null);

        sb.append("/").append(startPort);
        zk.addEphemeral(sb.toString(), null);
    }
}
