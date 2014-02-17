/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;

/**
 * ZooKeeper DAO class for Chains.
 */
public class ChainZkManager extends AbstractZkManager {

    public static class ChainConfig {

        // The chain name should only be used for logging.
        public String name = null;
        public Map<String, String> properties = new HashMap<String, String>();

        public ChainConfig() {
        }

        public ChainConfig(String name) {
            this.name = name;
        }
    }

    private final static Logger log =
        LoggerFactory.getLogger(ChainZkManager.class);

    /**
     * Constructor to set ZooKeeper and base path.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public ChainZkManager(ZkManager zk, PathBuilder paths,
                          Serializer serializer) {
        super(zk, paths, serializer);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new chain.
     *
     * @param id
     *            ID of the chain.
     * @param config
     *            ChainConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareChainCreate(UUID id, ChainConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(paths.getChainPath(id),
                serializer.serialize(config),
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getChainRulesPath(id),
                serializer.serialize(new RuleList()),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a chain deletion.
     *
     * @param id
     *            Chain ID
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareChainDelete(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        RuleZkManager ruleZkManager = new RuleZkManager(zk, paths, serializer);
        List<UUID> ruleIds = ruleZkManager.getRuleList(id).getRuleList();
        for (UUID ruleId : ruleIds) {
            Rule rule = ruleZkManager.get(ruleId);
            ops.addAll(ruleZkManager.prepareRuleDelete(ruleId, rule));
        }

        String chainRulePath = paths.getChainRulesPath(id);
        log.debug("Preparing to delete: " + chainRulePath);
        ops.add(Op.delete(chainRulePath, -1));

        String chainPath = paths.getChainPath(id);
        log.debug("Preparing to delete: " + chainPath);
        ops.add(Op.delete(chainPath, -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new chain entry.
     *
     * @param chain
     *            ChainConfig object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public UUID create(ChainConfig chain) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareChainCreate(id, chain));
        return id;
    }

    /**
     * Checks whether a chain with the given ID exists.
     *
     * @param id
     *            Chain ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getChainPath(id));
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a chain with the given ID.
     *
     * @param id
     *            The ID of the chain.
     * @return ChainConfig object found.
     * @throws StateAccessException
     */
    public ChainConfig get(UUID id) throws StateAccessException,
            SerializationException {
        byte[] data = zk.get(paths.getChainPath(id), null);
        return serializer.deserialize(data, ChainConfig.class);
    }

    /**
     * Updates the ChainConfig values with the given ChainConfig object.
     *
     * @param id
     * @param config
     *            ChainConfig object to save.
     * @throws StateAccessException
     */
    public void update(UUID id, ChainConfig config) throws StateAccessException,
            SerializationException {
        byte[] data = serializer.serialize(config);
        zk.update(paths.getChainPath(id), data);
    }

    /***
     * Deletes a chain and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the chain to delete.
     * @throws SerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id) throws StateAccessException,
            SerializationException
    {
        zk.multi(prepareChainDelete(id));
    }
}
