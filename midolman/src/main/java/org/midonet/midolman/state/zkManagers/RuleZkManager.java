/*
 * Copyright (c) 2011-2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.functors.Functor;

import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 */
public class RuleZkManager extends AbstractZkManager<UUID, Rule> {

    private final static Logger log = LoggerFactory
            .getLogger(RuleZkManager.class);

    ChainZkManager chainZkManager;
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
        chainZkManager = new ChainZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getRulePath(id);
    }

    @Override
    protected Class<Rule> getConfigClass() {
        return Rule.class;
    }

    public List<Op> prepareInsertPositionOrdering(UUID id, Rule ruleConfig,
                                                  int position)
            throws RuleIndexOutOfBoundsException, StateAccessException,
            SerializationException {
        // Make sure the position is greater than 0.
        if (position <= 0) {
            throw new RuleIndexOutOfBoundsException("Invalid rule position "
                    + position);
        }

        List<Op> ops = new ArrayList<>();

        // Add this one
        ops.addAll(prepareRuleCreate(id, ruleConfig));

        // Get the ordered list of rules for this chain
        Map.Entry<RuleList, Integer> ruleListWithVersion =
                getRuleListWithVersion(ruleConfig.chainId);
        List<UUID> ruleIds = ruleListWithVersion.getKey().getRuleList();
        int version = ruleListWithVersion.getValue();

        // If the new rule index is bigger than the max position by
        // more than 1, it's invalid.
        if (position > ruleIds.size() + 1) {
            throw new RuleIndexOutOfBoundsException("Invalid rule position "
                    + position);
        }

        // Create the new ordered list with the new item
        int insertionIndex = position -1;
        ruleIds.add(insertionIndex, id);
        String path = paths.getChainRulesPath(ruleConfig.chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
                version));

        return ops;
    }

    private List<Op> prepareDeletePositionOrdering(UUID id, Rule ruleConfig)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        // Delete this rule
        ops.addAll(prepareRuleDelete(id, ruleConfig));

        // Get the ordered list of rules for this chain
        Map.Entry<RuleList, Integer> ruleListWithVersion =
                getRuleListWithVersion(ruleConfig.chainId);
        List<UUID> ruleIds = ruleListWithVersion.getKey().getRuleList();
        int version = ruleListWithVersion.getValue();

        // Create the new ordered list without the deleted item
        ruleIds.remove(id);
        String path = paths.getChainRulesPath(ruleConfig.chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
                version));

        return ops;
    }

    public void prepareCreateRulesInNewChain(List<Op> ops, UUID chainId,
                                             List<Rule> rules)
            throws StateAccessException, SerializationException {

        // This method assumes that the chain provided does not exist yet and
        // it is in process of getting created.
        List<UUID> ruleIds = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            UUID id = UUID.randomUUID();
            rule.chainId = chainId;
            ops.addAll(prepareRuleCreate(id, rule));
            ruleIds.add(id);
        }

        // Chain does not exist yet, but it's assumed that it's initialized
        // to have an empty rule list.  Update the list.
        ops.add(Op.setData(paths.getChainRulesPath(chainId),
                serializer.serialize(new RuleList(ruleIds)), -1));

        //ops.add(Op.create(paths.getChainRulesPath(chainId),
        //        serializer.serialize(new RuleList(ruleIds)),
        //        Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
    }

    public void prepareReplaceRules(List<Op> ops, UUID chainId,
                                    List<Rule> rules)
            throws StateAccessException, SerializationException {

        // Get the current list of rules and remove them but without
        // updating the position order.
        Map.Entry<RuleList, Integer> list = getRuleListWithVersion(chainId);
        RuleList currentList = list.getKey();
        for (UUID ruleId : currentList.ruleList) {
            Rule r = get(ruleId);
            // Remove it but do not update rules list
            prepareDelete(ops, ruleId, r);
        }

        // Insert the new rules, and maintain their order
        List<UUID> ruleIds = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            UUID id = UUID.randomUUID();
            ops.addAll(prepareRuleCreate(id, rule));
            ruleIds.add(id);
        }

        // Update the rule list with the new order.  Fail if someone else
        // updated it
        int version = list.getValue();
        String path = paths.getChainRulesPath(chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
                version));
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
        List<Op> ops = new ArrayList<>();

        if (ruleConfig instanceof JumpRule) {
            JumpRule jrule = (JumpRule)ruleConfig;
            if (jrule.jumpToChainID != null) {
                ops.addAll(chainZkManager.prepareChainBackRefCreate(
                     jrule.jumpToChainID, ResourceType.RULE, id));
            }
        }

        log.debug("Preparing to create: " + rulePath);
        ops.add(simpleCreateOp(id, ruleConfig));

        // Add a reference entry to port group if port group is specified.
        UUID portGroupId = ruleConfig.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.create(
                    paths.getPortGroupRulePath(portGroupId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        }

        // Add a reference entry to the IP address group(s) if specified.
        UUID ipAddrGroupDstId = ruleConfig.getCondition().ipAddrGroupIdDst;
        if (ipAddrGroupDstId != null)
            ops.add(Op.create(
                    paths.getIpAddrGroupRulePath(ipAddrGroupDstId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        UUID ipAddrGroupSrcId = ruleConfig.getCondition().ipAddrGroupIdSrc;
        if (ipAddrGroupSrcId != null)
            ops.add(Op.create(
                    paths.getIpAddrGroupRulePath(ipAddrGroupSrcId, id), null,
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
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
    public void prepareDelete(List<Op> ops, UUID id, Rule rule)
            throws StateAccessException {
        String rulePath = paths.getRulePath(id);
        log.debug("Preparing to delete: " + rulePath);
        ops.add(Op.delete(rulePath, -1));

        if (rule instanceof JumpRule) {
            JumpRule jrule = (JumpRule)rule;
            if (jrule.jumpToChainID != null) {
                ops.addAll(chainZkManager.prepareChainBackRefDelete(
                        jrule.jumpToChainID, ResourceType.RULE, id));
            }
        }

        // Remove the reference to port group
        UUID portGroupId = rule.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.delete(
                    paths.getPortGroupRulePath(portGroupId, id), -1));
        }

        // Remove the reference(s) from the IP address group(s).
        UUID ipAddrGroupIdDst = rule.getCondition().ipAddrGroupIdDst;
        if (ipAddrGroupIdDst != null) {
            ops.add(Op.delete(
                    paths.getIpAddrGroupRulePath(ipAddrGroupIdDst, id), -1));
        }

        UUID ipAddrGroupIdSrc = rule.getCondition().ipAddrGroupIdSrc;
        if (ipAddrGroupIdSrc != null) {
            ops.add(Op.delete(
                    paths.getIpAddrGroupRulePath(ipAddrGroupIdSrc, id), -1));
        }
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
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
    public List<Op> prepareRuleDelete(UUID id, Rule rule)
        throws StateAccessException {
        List<Op> ops = new ArrayList<>();
        String rulePath = paths.getRulePath(id);
        log.debug("Preparing to delete: " + rulePath);
        ops.add(Op.delete(rulePath, -1));

        if (rule instanceof JumpRule) {
            JumpRule jrule = (JumpRule)rule;
            if (jrule.jumpToChainID != null) {
                ops.addAll(chainZkManager.prepareChainBackRefDelete(
                    jrule.jumpToChainID, ResourceType.RULE, id));
            }
        }

        // Remove the reference to port group
        UUID portGroupId = rule.getCondition().portGroup;
        if (portGroupId != null) {
            ops.add(Op.delete(
                    paths.getPortGroupRulePath(portGroupId, id), -1));
        }

        // Remove the reference(s) from the IP address group(s).
        UUID ipAddrGroupIdDst = rule.getCondition().ipAddrGroupIdDst;
        if (ipAddrGroupIdDst != null) {
            ops.add(Op.delete(
                    paths.getIpAddrGroupRulePath(ipAddrGroupIdDst, id), -1));
        }

        UUID ipAddrGroupIdSrc = rule.getCondition().ipAddrGroupIdSrc;
        if (ipAddrGroupIdSrc != null) {
            ops.add(Op.delete(
                    paths.getIpAddrGroupRulePath(ipAddrGroupIdSrc, id), -1));
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
    public UUID create(Rule rule, int position)
            throws RuleIndexOutOfBoundsException,
            StateAccessException, SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareInsertPositionOrdering(id, rule, position));
        return id;
    }

    public void getRuleIdListAsync(
            UUID chainId,
            DirectoryCallback<List<UUID>> ruleIdsCallback,
            Directory.TypedWatcher watcher) {
        String path = paths.getChainRulesPath(chainId);

        zk.asyncGet(
                path,
                DirectoryCallbackFactory.transform(
                        ruleIdsCallback,
                        new Functor<byte[], List<UUID>>() {
                            @Override
                            public List<UUID> apply(byte[] arg0) {
                                try {
                                    return serializer.deserialize(arg0,
                                            RuleList.class).getRuleList();
                                } catch (SerializationException e) {
                                    log.warn("Could not deserialize RuleList data");
                                }
                                return null;
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
    public Map.Entry<RuleList, Integer> getRuleListWithVersion(UUID chainId,
            Runnable watcher) throws StateAccessException {
        String path = paths.getChainRulesPath(chainId);
        Map.Entry<byte[], Integer> ruleIdsVersion = zk.getWithVersion(path,
                watcher);

        byte[] data = ruleIdsVersion.getKey();
        int version = ruleIdsVersion.getValue();

        // convert
        try {
            RuleList ruleList = serializer.deserialize(data,
                    RuleList.class);
            return new AbstractMap.SimpleEntry<>(ruleList, version);
        } catch (SerializationException e) {
            log.error("Could not deserialize rule list {}", data, e);
            return null;
        }
    }

    public RuleList getRuleList(UUID chainId) throws StateAccessException {
        return getRuleListWithVersion(chainId, null).getKey();
    }

    public Map.Entry<RuleList, Integer> getRuleListWithVersion(UUID chainId)
            throws StateAccessException {
        return getRuleListWithVersion(chainId, null);
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
        zk.multi(prepareDelete(id));
    }

}
