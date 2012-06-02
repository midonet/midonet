/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 */
public class ChainZkManager extends ZkManager {

    public static class ChainConfig {

        // The chain name should only be used for logging.
        public String name = null;

        public ChainConfig() {
        }

        public ChainConfig(String name) {
            this.name = name;
        }
    }

    private final static Logger log = LoggerFactory
            .getLogger(ChainZkManager.class);

    /**
     * Constructor to set ZooKeeper and base path.
     *
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public ChainZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new chain.
     *
     * @param chainEntry
     *            ZooKeeper node representing a key-value entry of chain UUID
     *            and ChainConfig object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareChainCreate(ZkNodeEntry<UUID, ChainConfig> chainEntry)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getChainPath(chainEntry.key),
                serializer.serialize(chainEntry.value), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getChainRulesPath(chainEntry.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    public List<Op> prepareChainDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareChainDelete(get(id));
    }

    /**
     * Constructs a list of operations to perform in a chain deletion.
     *
     * @param entry
     *            Chain ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public List<Op> prepareChainDelete(ZkNodeEntry<UUID, ChainConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        RuleZkManager ruleZkManager = new RuleZkManager(zk,
                pathManager.getBasePath());
        Set<UUID> ruleIds = ruleZkManager.getRuleIds(entry.key);
        for (UUID ruleId : ruleIds) {
            Rule rule = ruleZkManager.get(ruleId);
            ops.addAll(ruleZkManager.prepareRuleDelete(ruleId, rule));
        }

        String chainRulePath = pathManager.getChainRulesPath(entry.key);
        log.debug("Preparing to delete: " + chainRulePath);
        ops.add(Op.delete(chainRulePath, -1));

        String chainPath = pathManager.getChainPath(entry.key);
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
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public UUID create(ChainConfig chain) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, ChainConfig> chainNode = new ZkNodeEntry<UUID, ChainConfig>(
                id, chain);
        multi(prepareChainCreate(chainNode));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a chain with the given ID.
     *
     * @param id
     *            The ID of the chain.
     * @return ChainConfig object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public ZkNodeEntry<UUID, ChainConfig> get(UUID id)
            throws StateAccessException {
        byte[] data = get(pathManager.getChainPath(id), null);
        ChainConfig config = serializer.deserialize(data, ChainConfig.class);
        return new ZkNodeEntry<UUID, ChainConfig>(id, config);
    }

    /**
     * Updates the ChainConfig values with the given ChainConfig object.
     *
     * @param entry
     *            ChainConfig object to save.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void update(ZkNodeEntry<UUID, ChainConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = serializer.serialize(entry.value);
        update(pathManager.getChainPath(entry.key), data);
    }

    /***
     * Deletes a chain and its related data from the ZooKeeper directories
     * atomically.
     *
     * @param id
     *            ID of the chain to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     */
    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareChainDelete(id));
    }
}
