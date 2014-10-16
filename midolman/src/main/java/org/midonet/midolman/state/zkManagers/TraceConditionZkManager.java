/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
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
public class TraceConditionZkManager
        extends AbstractZkManager<UUID, Condition> {

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
                                   Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getTraceConditionPath(id);
    }

    @Override
    protected Class<Condition> getConfigClass() {
        return Condition.class;
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
    public List<Op> prepareCreate(UUID id, Condition condition)
            throws StateAccessException, SerializationException {
        return Arrays.asList(simpleCreateOp(id, condition));
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
            throws StateAccessException, SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareCreate(id, condition));
        return id;
    }

    /**
     * Constructs a list of operations to perform in a trace condition deletion
     *
     * @param id Trace condition ID
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        String traceConditionPath = paths.getTraceConditionPath(id);
        log.debug("Preparing to delete: " + traceConditionPath);
        return Arrays.asList(Op.delete(traceConditionPath, -1));
    }

    /**
     * Gets a list of trace condition ids
     * @return Trace condition ID set
     * @throws StateAccessException
     */
    public List<UUID> getIds() throws StateAccessException {
        log.debug("Getting trace condition ID's.");
        return getUuidList(paths.getTraceConditionsPath());
    }

    /**
     * Gets a set of trace conditions
     * @param watcher Runnable to execute on updates
     * @return Trace condition set
     * @throws StateAccessException, SerializationException
     */
    public List<Condition> getConditions(Runnable watcher)
            throws StateAccessException, SerializationException {
        log.debug("Getting trace conditions list.");
        String path = paths.getTraceConditionsPath();
        Set<String> idSet = zk.getChildren(path, watcher);
        List<Condition> conditions = new ArrayList<Condition>(idSet.size());
        for (String id : idSet) {
            log.debug("Fetch trace condition {}", id);
            conditions.add(get(UUID.fromString(id)));
        }

        return conditions;
    }

    /**
     * Deletes a trace condition from ZooKeeper
     *
     * @param id ID of the trace condition to delete
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id)
        throws SerializationException, StateAccessException {
        zk.multi(prepareDelete(id));
    }
}
