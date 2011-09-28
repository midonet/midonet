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
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(RuleZkManager.class);

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

    private List<Op> prepareInsertPositionOrdering(
            ZkNodeEntry<UUID, Rule> ruleEntry)
            throws ZkStateSerializationException, StateAccessException,
            RuleIndexOutOfBoundsException {
        // Make sure the position is greater than 0;
        int position = ruleEntry.value.position;
        if (position <= 0) {
            throw new RuleIndexOutOfBoundsException("Invalid rule position "
                    + position);
        }

        List<Op> ops = new ArrayList<Op>();
        // Add this one
        ops.addAll(prepareRuleCreate(ruleEntry));

        // Get all the rules for this chain
        List<ZkNodeEntry<UUID, Rule>> rules = list(ruleEntry.value.chainId);

        int max = 0;
        for (ZkNodeEntry<UUID, Rule> rule : rules) {
            if (rule.value.position > max) {
                max = rule.value.position;
            }
            // For any node that has the >= position value, shift up.
            if (rule.value.position >= position) {
                String path = pathManager.getChainRulePath(rule.value.chainId,
                        rule.key);
                rule.value.position++;
                try {
                    ops.add(Op.setData(path, serialize(rule), -1));
                } catch (IOException e) {
                    throw new ZkStateSerializationException(
                            "Could not serialize Rule", e, Rule.class);
                }
            }
        }
        // If the new rule index is bigger than the max position by
        // more than 1, it's invalid.
        if (position > max + 1) {
            throw new RuleIndexOutOfBoundsException("Invalid rule position "
                    + position);
        }
        return ops;
    }

    private List<Op> prepareDeletePositionOrdering(
            ZkNodeEntry<UUID, Rule> ruleEntry)
            throws ZkStateSerializationException, StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        // Delete this one
        ops.addAll(prepareRuleDelete(ruleEntry));

        // Get all the rules for this chain
        List<ZkNodeEntry<UUID, Rule>> rules = list(ruleEntry.value.chainId);

        int position = ruleEntry.value.position;
        for (ZkNodeEntry<UUID, Rule> rule : rules) {
            // For any node that has the > position value, shift down.
            if (rule.value.position > position) {
                String path = pathManager.getChainRulePath(rule.value.chainId,
                        rule.key);
                rule.value.position--;
                try {
                    ops.add(Op.setData(path, serialize(rule), -1));
                } catch (IOException e) {
                    throw new ZkStateSerializationException(
                            "Could not serialize Rule", e, Rule.class);
                }
            }
        }

        return ops;
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
     */
    public List<Op> prepareRuleCreate(ZkNodeEntry<UUID, Rule> ruleEntry)
            throws ZkStateSerializationException {
        String rulePath = pathManager.getRulePath(ruleEntry.key);
        String chainRulePath = pathManager.getChainRulePath(
                ruleEntry.value.chainId, ruleEntry.key);
        List<Op> ops = new ArrayList<Op>();
        log.debug("Preparing to create: " + rulePath);
        try {
            ops.add(Op.create(rulePath, serialize(ruleEntry.value),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize Rule",
                    e, Rule.class);
        }

        log.debug("Preparing to create: " + chainRulePath);
        ops.add(Op.create(chainRulePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        return ops;
    }

    public List<Op> prepareRuleDelete(UUID id)
            throws ZkStateSerializationException, StateAccessException {
        return prepareDeletePositionOrdering(get(id));
    }

    /**
     * Constructs a list of operations to perform in a rule deletion.
     * 
     * @param entry
     *            Rule ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws ZkStateSerializationException
     *             Serialization error occurred.
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
     * @throws StateAccessException
     * @throws RuleIndexOutOfBoundsException
     */
    public UUID create(Rule rule) throws ZkStateSerializationException,
            StateAccessException, RuleIndexOutOfBoundsException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, Rule> ruleNode = new ZkNodeEntry<UUID, Rule>(id, rule);
        multi(prepareInsertPositionOrdering(ruleNode));
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
     * @throws StateAccessException
     */
    public ZkNodeEntry<UUID, Rule> get(UUID id)
            throws ZkStateSerializationException, StateAccessException {
        byte[] data = get(pathManager.getRulePath(id), null);
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
     * @throws StateAccessException
     */
    public List<ZkNodeEntry<UUID, Rule>> list(UUID chainId)
            throws ZkStateSerializationException, StateAccessException {
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
     * @throws StateAccessException
     */
    public List<ZkNodeEntry<UUID, Rule>> list(UUID chainId, Runnable watcher)
            throws ZkStateSerializationException, StateAccessException {
        List<ZkNodeEntry<UUID, Rule>> result = new ArrayList<ZkNodeEntry<UUID, Rule>>();
        Set<String> rules = getChildren(pathManager.getChainRulesPath(chainId),
                watcher);
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
     * @throws StateAccessException
     */
    public void delete(UUID id) throws ZkStateSerializationException,
            StateAccessException {
        multi(prepareRuleDelete(id));
    }

}
