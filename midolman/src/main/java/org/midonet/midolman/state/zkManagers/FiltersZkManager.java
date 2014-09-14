/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Class to manage the ZK data for the implicit filters of Ports, Bridges,
 * and Routers.
 */
public class FiltersZkManager extends BaseZkManager {

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
    @Inject
    public FiltersZkManager(ZkManager zk, PathBuilder paths, Serializer serializer) {
        super(zk, paths, serializer);
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
        return zk.getPersistentCreateOps(paths.getFilterPath(id));
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
        String filterPath = paths.getFilterPath(id);
        return Arrays.asList(Op.delete(filterPath, -1));
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
}
