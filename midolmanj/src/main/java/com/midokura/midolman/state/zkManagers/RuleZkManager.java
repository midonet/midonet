/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
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

    private List<Op> prepareInsertPositionOrdering(UUID id, Rule ruleConfig)
            throws RuleIndexOutOfBoundsException, StateAccessException {
        // Make sure the position is greater than 0.
        int position = ruleConfig.position;
        if (position <= 0) {
            throw new RuleIndexOutOfBoundsException("Invalid rule position "
                    + position);
        }

        List<Op> ops = new ArrayList<Op>();
        // Add this one
        ops.addAll(prepareRuleCreate(id, ruleConfig));

        // Get all the rules for this chain
        Set<UUID> ruleIds = getRuleIds(ruleConfig.chainId);

        int max = 0;
        for (UUID ruleId : ruleIds) {
            Rule rule = get(ruleId);
            if (rule.position > max) {
                max = rule.position;
            }
            // For any node that has the >= position value, shift up.
            if (rule.position >= position) {
                String path = pathManager.getRulePath(ruleId);
                (rule.position)++;
                ops.add(Op.setData(path, serializer.serialize(rule), -1));

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

    private List<Op> prepareDeletePositionOrdering(UUID id, Rule ruleConfig)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();
        // Delete this one
        ops.addAll(prepareRuleDelete(id, ruleConfig));

        // Get all the rules for this chain
        Set<UUID> ruleIds = getRuleIds(ruleConfig.chainId);

        int position = ruleConfig.position;
        for (UUID ruleId : ruleIds) {
            Rule rule = get(ruleId);
            // For any rule that has position > the deleted rule, shift down.
            if (rule.position > position) {
                String path = pathManager.getRulePath(ruleId);
                rule.position--;
                ops.add(Op.setData(path, serializer.serialize(rule), -1));
            }
        }

        return ops;
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new rule. This method does not re-number the positions of other rules in
     * the same chain.
     *
     * @param id
     *            Rule ID
     * @param ruleConfig
     *            ZooKeeper node value representing a rule.
     * @return A list of Op objects to represent the operations to perform.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error occurred.
     */
    private List<Op> prepareRuleCreate(UUID id, Rule ruleConfig)
            throws ZkStateSerializationException {
        String rulePath = pathManager.getRulePath(id);
        String chainRulePath = pathManager.getChainRulePath(ruleConfig.chainId,
                id);
        List<Op> ops = new ArrayList<Op>();
        log.debug("Preparing to create: " + rulePath);
        ops.add(Op.create(rulePath, serializer.serialize(ruleConfig),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        log.debug("Preparing to create: " + chainRulePath);
        ops.add(Op.create(chainRulePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Add a reference entry to port group if port group is specified.
        UUID portGroupId = ruleConfig.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.create(
                    pathManager.getPortGroupRulePath(portGroupId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }

        return ops;
    }

    public List<Op> prepareRuleDelete(UUID id) throws StateAccessException {
        return prepareDeletePositionOrdering(id, get(id));
    }

    /**
     * Constructs a list of operations to perform in a rule deletion. This
     * method does not re-number the positions of other rules in the same chain.
     * The method is package-private so that it can be used for deleting an
     * entire rule-chain.
     *
     * @param rule
     *            Rule ZooKeeper entry to delete.
     * @return A list of Op objects representing the operations to perform.
     */
    public List<Op> prepareRuleDelete(UUID id, Rule rule) {
        List<Op> ops = new ArrayList<Op>();
        String chainRulePath = pathManager.getChainRulePath(rule.chainId, id);
        log.debug("Preparing to delete: " + chainRulePath);
        ops.add(Op.delete(chainRulePath, -1));
        String rulePath = pathManager.getRulePath(id);
        log.debug("Preparing to delete: " + rulePath);
        ops.add(Op.delete(rulePath, -1));

        // Remove the reference to port group
        UUID portGroupId = rule.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.delete(
                    pathManager.getPortGroupRulePath(portGroupId, id), -1));
        }

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new rule entry. This
     * method may re-number the positions of other rules in the same chain in
     * order to insert the new rule at the desired position.
     *
     * @param rule
     *            Rule object to add to the ZooKeeper directory.
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     * @throws RuleIndexOutOfBoundsException
     */
    public UUID create(Rule rule) throws RuleIndexOutOfBoundsException,
            StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareInsertPositionOrdering(id, rule));
        return id;
    }

    /**
     * Gets a ZooKeeper node entry key-value pair of a rule with the given ID.
     *
     * @param id
     *            The ID of the rule.
     * @return Rule object found.
     * @throws StateAccessException
     */
    public Rule get(UUID id) throws StateAccessException {
        byte[] data = get(pathManager.getRulePath(id), null);
        return serializer.deserialize(data, Rule.class);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(pathManager.getRulePath(id));
    }

    /**
     * Gets a list of ZooKeeper rule nodes belonging to a chain with the given
     * ID.
     *
     * @param chainId
     *            The ID of the chain to find the rules of.
     * @return A list of rule IDs
     * @throws StateAccessException
     */
    public Set<UUID> getRuleIds(UUID chainId, Runnable watcher)
            throws StateAccessException {
        Set<UUID> result = new HashSet<UUID>();
        String path = pathManager.getChainRulesPath(chainId);
        Set<String> ruleIds = getChildren(path, watcher);
        for (String ruleId : ruleIds) {
            result.add(UUID.fromString(ruleId));
        }
        return result;
    }

    public Set<UUID> getRuleIds(UUID chainId) throws StateAccessException {
        return getRuleIds(chainId, null);
    }

    /***
     * Deletes a rule and its related data from the ZooKeeper directories
     * atomically. This method may re-number the positions of other rules in the
     * same chain.
     *
     * @param id
     *            ID of the rule to delete.
     * @throws StateAccessException
     */
    public void delete(UUID id) throws StateAccessException {
        multi(prepareRuleDelete(id));
    }

}
