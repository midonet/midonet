/*
 * Copyright 2013 Midokura Pte Ltd
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZooKeeper DAO class for trace conditions
 */
public class TraceConditionZkManager extends AbstractZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(TraceConditionZkManager.class);

    /**
     * TraceConditionZkManager constructor.
     *
     * @param zk ZooKeeper data access class
     * @param paths PathBuilder class to construct ZooKeeper paths
     * @param serializer Data serialization class
     */
    public TraceConditionZkManager(ZkManager zk, PathBuilder paths,
                                   Serializer serializer)
    {
        super(zk, paths, serializer);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new trace condition.
     *
     * @param id Trace condition identifier
     * @param condition Trace condition
     * @return A list of Op objects to represent the operations to perform
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareTraceConditionCreate(UUID id, Condition condition)
        throws StateAccessException, SerializationException
    {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getTracedConditionPath(id),
                serializer.serialize(condition),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a trace condition deletion
     *
     * @param id Trace condition ID
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareTraceConditionDelete(UUID id)
        throws StateAccessException
    {
        List<Op> ops = new ArrayList<Op>();

        String traceConditionPath = paths.getTracedConditionPath(id);
        log.debug("Preparing to delete: " + traceConditionPath);
        ops.add(Op.delete(traceConditionPath, -1));

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new trace condition
     * entry
     *
     * @param condition Trace condition object to add to Zookeeper
     * @return The UUID of the newly created object.
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public UUID create(Condition condition)
        throws StateAccessException, SerializationException
    {
        UUID id = UUID.randomUUID();
        zk.multi(prepareTraceConditionCreate(id, condition));
        return id;
    }

    /**
     * Checks whether a trace condition with the given ID exists
     *
     * @param id Trace condition id to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getTracedConditionPath(id));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a trace condition with the
     * given id
     *
     * @param id The id of the trace condition
     * @return Condition object corresponding to the id
     * @throws StateAccessException
     */
    public Condition get(UUID id)
        throws StateAccessException, SerializationException
    {
        byte[] data = zk.get(paths.getTracedConditionPath(id), null);
        return serializer.deserialize(data, Condition.class);
    }

    /**
     * Gets a list of trace condition ids
     * @return Trace condition ID set
     * @throws StateAccessException
     */
    public Set<UUID> getIds() throws StateAccessException {
        String path = paths.getTracedConditionsPath();
        Set<String> idSet = zk.getChildren(path);
        Set<UUID> ids = new HashSet<UUID>(idSet.size());
        for (String id : idSet) {
            ids.add(UUID.fromString(id));
        }

        return ids;
    }

    /**
     * Gets a set of trace conditions
     * @param watcher Runnable to execute on updates
     * @return Trace condition set
     * @throws StateAccessException, SerializationException
     */
    public Set<Condition> getConditions(Runnable watcher)
            throws StateAccessException, SerializationException {
        String path = paths.getTracedConditionsPath();
        Set<String> idSet = zk.getChildren(path, watcher);
        Set<Condition> conditions = new HashSet<Condition>(idSet.size());
        for (String id : idSet) {
            conditions.add(get(UUID.fromString(id)));
        }

        return conditions;
    }

    /***
     * Deletes a trace condition ZooKeeper
     *
     * @param id ID of the trace condition to delete
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id)
        throws StateAccessException
    {
        zk.multi(prepareTraceConditionDelete(id));
    }
}
