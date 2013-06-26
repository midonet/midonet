/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.RuleIndexOutOfBoundsException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 */
public class RuleZkManager extends AbstractZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(RuleZkManager.class);

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
    public RuleZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    public RuleZkManager(Directory dir, String basePath,
                         Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    private List<Op> prepareInsertPositionOrdering(UUID id, Rule ruleConfig)
            throws RuleIndexOutOfBoundsException, StateAccessException,
            SerializationException {
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
                String path = paths.getRulePath(ruleId);
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
            throws StateAccessException, SerializationException {
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
                String path = paths.getRulePath(ruleId);
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
     * @throws org.midonet.midolman.serialization.SerializationException
     *             Serialization error occurred.
     */
    private List<Op> prepareRuleCreate(UUID id, Rule ruleConfig)
            throws StateAccessException, SerializationException {
        String rulePath = paths.getRulePath(id);
        String chainRulePath = paths.getChainRulePath(ruleConfig.chainId,
                id);
        List<Op> ops = new ArrayList<Op>();
        log.debug("Preparing to create: " + rulePath);
        ops.add(Op.create(rulePath,
                serializer.serialize(ruleConfig),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        log.debug("Preparing to create: " + chainRulePath);
        ops.add(Op.create(chainRulePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Add a reference entry to port group if port group is specified.
        UUID portGroupId = ruleConfig.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.create(
                    paths.getPortGroupRulePath(portGroupId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }

        return ops;
    }

    public List<Op> prepareRuleDelete(UUID id) throws StateAccessException,
            SerializationException {
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
        String chainRulePath = paths.getChainRulePath(rule.chainId, id);
        log.debug("Preparing to delete: " + chainRulePath);
        ops.add(Op.delete(chainRulePath, -1));
        String rulePath = paths.getRulePath(id);
        log.debug("Preparing to delete: " + rulePath);
        ops.add(Op.delete(rulePath, -1));

        // Remove the reference to port group
        UUID portGroupId = rule.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.delete(
                    paths.getPortGroupRulePath(portGroupId, id), -1));
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
            StateAccessException, SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareInsertPositionOrdering(id, rule));
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
    public Rule get(UUID id) throws StateAccessException,
            SerializationException {
        byte[] data = zk.get(paths.getRulePath(id), null);
        return serializer.deserialize(data, Rule.class);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getRulePath(id));
    }

    public void getRuleAsync(
            UUID ruleId,
            DirectoryCallback<Rule> ruleCallback,
            Directory.TypedWatcher watcher) {

        String path = paths.getRulePath(ruleId);

        zk.asyncGet(
            path,
            DirectoryCallbackFactory.transform(
                ruleCallback,
                new Functor<byte[], Rule>() {
                    @Override
                    public Rule apply(byte[] arg0) {
                        try {
                            return serializer.deserialize(arg0, Rule.class);
                        } catch (SerializationException e) {
                            log.warn("Could not deserialize Rule data");
                        }
                        return null;
                    }
                }),
            watcher);
    }

    public void getRuleIdListAsync(
            UUID chainId,
            DirectoryCallback<Set<UUID>> ruleIdsCallback,
            Directory.TypedWatcher watcher) {
        String path = paths.getChainRulesPath(chainId);

        zk.asyncGetChildren(
            path,
            DirectoryCallbackFactory.transform(
                ruleIdsCallback,
                new Functor<Set<String>, Set<UUID>>() {
                    @Override
                    public Set<UUID> apply(Set<String> arg0) {
                        return CollectionFunctors.map(
                            arg0, strToUUIDMapper, new HashSet<UUID>());
                    }
                }),
            watcher);
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
        String path = paths.getChainRulesPath(chainId);
        Set<String> ruleIds = zk.getChildren(path, watcher);
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
    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareRuleDelete(id));
    }

}
