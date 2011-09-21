/*
 * @(#)RuleZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleZkManager extends ZkManager {

    /**
     * Constructor to set ZooKeeper and base path.
     * 
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public RuleZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public RuleZkManager(ZooKeeper zk, String basePath) {
        this(new ZkDirectory(zk, "", null), basePath);
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new rule.
     * 
     * @param ruleEntry
     *            ZooKeeper node representing a key-value entry of rule UUID and
     *            Rule object.
     * @return A list of Op objects to represent the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRuleCreate(ZkNodeEntry<UUID, Rule> ruleEntry)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getRulePath(ruleEntry.key),
                    serialize(ruleEntry.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize Rule",
                    e, Rule.class);
        }
        ops.add(Op.create(pathManager.getChainRulePath(ruleEntry.value.chainId,
                ruleEntry.key), null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        return ops;
    }


    /**
     * Constructs a list of operations to perform in a rule deletion.
     * 
     * @param entry
     *            Rule ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<Op> prepareRuleDelete(ZkNodeEntry<UUID, Rule> entry) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getChainRulePath(entry.value.chainId,
                entry.key), -1));
        ops.add(Op.delete(pathManager.getRulePath(entry.key), -1));
        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new rule entry.
     * 
     * @param rule
     *            Rule object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public UUID create(Rule rule) throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, Rule> ruleNode = new ZkNodeEntry<UUID, Rule>(id, rule);
        this.zk.multi(prepareRuleCreate(ruleNode));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a rule with the given ID.
     * 
     * @param id
     *            The ID of the rule.
     * @return Rule object found.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public ZkNodeEntry<UUID, Rule> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.get(pathManager.getRulePath(id), null);
        Rule rule = null;
        try {
            rule = deserialize(data, Rule.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize chain " + id + " to Rule", e,
                    Rule.class);
        }
        return new ZkNodeEntry<UUID, Rule>(id, rule);
    }

    /**
     * Gets a list of ZooKeeper rule nodes belonging to a chain with the given
     * ID.
     * 
     * @param chainId
     *            The ID of the chain to find the rules of.
     * @return A list of ZooKeeper chain nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, Rule>> list(UUID chainId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return list(chainId, null);
    }

    /**
     * Gets a list of ZooKeeper rule nodes belonging to a chain with the given
     * ID.
     * 
     * @param chainId
     *            The ID of the chain to find the rules of.
     * @param watcher
     *            The watcher to set on the changes to the rules for this chain.
     * @return A list of ZooKeeper chain nodes.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public List<ZkNodeEntry<UUID, Rule>> list(UUID chainId, Runnable watcher)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Rule>> result = new ArrayList<ZkNodeEntry<UUID, Rule>>();
        Set<String> rules = zk.getChildren(pathManager
                .getChainRulesPath(chainId), watcher);
        for (String rule : rules) {
            // For now, get each one.
            result.add(get(UUID.fromString(rule)));
        }
        return result;
    }

    /***
     * Deletes a rule and its related data from the ZooKeeper directories
     * atomically.
     * 
     * @param id
     *            ID of the rule to delete.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
     * @throws KeeperException
     *             ZooKeeper error occurred.
     * @throws InterruptedException
     *             ZooKeeper was unresponsive.
     */
    public void delete(UUID id) throws InterruptedException, KeeperException,
            ClassNotFoundException, ZkStateSerializationException {
        this.zk.multi(prepareRuleDelete(get(id)));
    }

}
