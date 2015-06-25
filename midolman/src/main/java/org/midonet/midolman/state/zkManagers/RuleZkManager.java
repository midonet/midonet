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

import java.util.*;

import com.google.common.base.Function;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.rules.*;
import org.midonet.packets.IPv4Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.RuleMatcher.DefaultDropRuleMatcher;
import org.midonet.midolman.rules.RuleMatcher.DnatRuleMatcher;
import org.midonet.midolman.rules.RuleMatcher.DropFragmentRuleMatcher;
import org.midonet.midolman.rules.RuleMatcher.ReverseSnatRuleMatcher;
import org.midonet.midolman.rules.RuleMatcher.SnatRuleMatcher;
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

    public void prepareRulesAppendToEndOfChain(List<Op> ops, UUID chainId,
                                                   List<Rule> rules)
            throws RuleIndexOutOfBoundsException, StateAccessException,
            SerializationException {

        List<UUID> newRuleIds = new ArrayList<>();
        for (Rule r : rules) {
            UUID id = UUID.randomUUID();
            newRuleIds.add(id);
            ops.addAll(prepareRuleCreate(id, r));
        }

        // Get the ordered list of rules for this chain
        Map.Entry<RuleList, Integer> ruleListWithVersion =
            getRuleListWithVersion(chainId);
        List<UUID> ruleIds = ruleListWithVersion.getKey().getRuleList();
        int version = ruleListWithVersion.getValue();

        ruleIds.addAll(newRuleIds);

        String path = paths.getChainRulesPath(chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
            version));
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
            UUID id = rule.getCondition().id == null ? UUID.randomUUID() : rule.getCondition().id;
            rule.chainId = chainId;
            ops.addAll(prepareRuleCreate(id, rule));
            ruleIds.add(id);
        }

        // Chain does not exist yet, but it's assumed that it's initialized
        // to have an empty rule list.  Update the list.
        ops.add(Op.setData(paths.getChainRulesPath(chainId),
                serializer.serialize(new RuleList(ruleIds)), -1));
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
            rule.chainId = chainId;
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
     */
    private void prepareDelete(List<Op> ops, UUID id, Rule rule)
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
    public UUID create(UUID id, Rule rule, int position)
            throws RuleIndexOutOfBoundsException,
            StateAccessException, SerializationException {
        UUID ruleId = id == null ? UUID.randomUUID() : id;
        zk.multi(prepareInsertPositionOrdering(ruleId, rule, position));
        return ruleId;
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

        if (!zk.exists(path)) {
            // In this case we are creating the rule list along with this op
            return new AbstractMap.SimpleEntry<>(new RuleList(), -1);
        }

        Map.Entry<byte[], Integer> ruleIdsVersion = zk.getWithVersion(path,
            watcher);
        byte[] data = ruleIdsVersion.getKey();
        int version = ruleIdsVersion.getValue();

        // convert
        try {
            RuleList ruleList = serializer.deserialize(data, RuleList.class);
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

    public UUID prepareReplaceDnatRules(List<Op> ops, UUID chainId, UUID portId,
                                        IPv4Addr matchIp, IPv4Addr oldTarget,
                                        IPv4Addr newTarget)
            throws SerializationException, StateAccessException {
        Rule r = new RuleBuilder(chainId)
            .comingInPort(portId)
            .toIp(matchIp)
            .destNat(new NatTarget(newTarget, 0, 0));
        return prepareReplaceRules(ops, chainId,
            new DnatRuleMatcher(oldTarget), r);
    }

    public UUID prepareReplaceSnatRules(List<Op> ops, UUID chainId, UUID portId,
                                        IPv4Addr matchIp, IPv4Addr oldTarget,
                                        IPv4Addr newTarget)
            throws SerializationException, StateAccessException {
        Rule r = new RuleBuilder(chainId)
            .goingOutPort(portId)
            .fromIp(matchIp)
            .sourceNat(new NatTarget(newTarget, 0, 0));
        return prepareReplaceRules(ops, chainId,
            new SnatRuleMatcher(oldTarget), r);
    }

    public void prepareDeleteDnatRules(List<Op> ops, UUID chainId,
                                       IPv4Addr target)
            throws SerializationException, StateAccessException {
        prepareDeleteRules(ops, chainId,
            new DnatRuleMatcher(target));
    }

    public void prepareDeleteSnatRules(List<Op> ops, UUID chainId,
                                       IPv4Addr addr)
            throws SerializationException, StateAccessException {
        prepareDeleteRules(ops, chainId, new SnatRuleMatcher(addr));
    }

    public void prepareDeleteRules(List<Op> ops, UUID chainId,
                                   Function<Rule, Boolean> matcher)
            throws StateAccessException, SerializationException {
        prepareReplaceRules(ops, chainId, matcher, null);
    }

    public boolean snatRuleExists(UUID chainId, IPv4Addr addr)
            throws StateAccessException, SerializationException {
        RuleList ruleList = getRuleList(chainId);
        NatTarget target = new NatTarget(addr);
        SnatRuleMatcher matcher = new SnatRuleMatcher(target);
        for (UUID rId : ruleList.getRuleList()) {
            Rule r = get(rId);
            if (matcher.apply(r)) {
                return true;
            }
        }
        return false;
    }

    public void prepareDeleteRules(List<Op> ops, UUID chainId,
                                   List<RuleMatcher> matchers)
            throws StateAccessException, SerializationException {

        // Get the ordered list of rules for this chain
        Map.Entry<RuleList, Integer> ruleListWithVersion =
            getRuleListWithVersion(chainId);
        int version = ruleListWithVersion.getValue();
        RuleList list = ruleListWithVersion.getKey();
        List<UUID> ruleIds = list.getRuleList();

        Iterator<UUID> i = ruleIds.iterator();
        while (i.hasNext()) {
            UUID ruleId = i.next();
            Rule r = get(ruleId);
            for (Function<Rule, Boolean> matcher : matchers) {
                if (matcher.apply(r)) {
                    prepareDelete(ops, ruleId, r);
                    i.remove();
                }
            }
        }

        String path = paths.getChainRulesPath(chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
            version));
    }

    private UUID prepareReplaceRules(List<Op> ops, UUID chainId,
                                     Function<Rule, Boolean> matcher,
                                     Rule newRule)
            throws StateAccessException, SerializationException {
        // Get the ordered list of rules for this chain
        Map.Entry<RuleList, Integer> ruleListWithVersion =
                getRuleListWithVersion(chainId);
        int version = ruleListWithVersion.getValue();
        RuleList list = ruleListWithVersion.getKey();
        List<UUID> ruleIds = list.getRuleList();
        int firstIdx = -1;
        Iterator<UUID> i = ruleIds.iterator();
        while (i.hasNext()) {
            UUID ruleId = i.next();
            Rule r = get(ruleId);
            if (matcher.apply(r)) {
                prepareDelete(ops, ruleId, r);
                if (firstIdx < 0) {
                    firstIdx = ruleIds.indexOf(ruleId);
                }
                i.remove();
            }
        }

        // When newRule is supplied, this is inserted in the list.
        // This seems arbitrary but useful if you want replace the existing
        // rules with a new rule after deletion.
        UUID newId = null;
        if (newRule != null) {
            newId = UUID.randomUUID();
            int index = (firstIdx < 0) ? 0 : firstIdx;
            ruleIds.add(index, newId);
            ops.addAll(prepareRuleCreate(newId, newRule));
        }

        String path = paths.getChainRulesPath(chainId);
        ops.add(Op.setData(path, serializer.serialize(new RuleList(ruleIds)),
                version));

        return newId;
    }

    private UUID prepareCreateRuleFirstPosition(List<Op> ops, Rule rule)
            throws RuleIndexOutOfBoundsException, SerializationException,
            StateAccessException {

        UUID id = UUID.randomUUID();
        ops.addAll(prepareInsertPositionOrdering(id, rule, 1));
        return id;
    }

    public UUID prepareCreateStaticSnatRule(List<Op> ops, UUID chainId,
                                            UUID portId, IPv4Addr matchIp,
                                            IPv4Addr targetIp)
            throws RuleIndexOutOfBoundsException, SerializationException,
            StateAccessException {
        Rule rule = new RuleBuilder(chainId)
            .goingOutPort(portId)
            .fromIp(matchIp)
            .sourceNat(new NatTarget(targetIp, 0, 0));
        return prepareCreateRuleFirstPosition(ops, rule);
    }

    public UUID prepareCreateStaticDnatRule(List<Op> ops, UUID chainId,
                                            UUID portId, IPv4Addr matchIp,
                                            IPv4Addr targetIp)
            throws RuleIndexOutOfBoundsException, SerializationException,
            StateAccessException {
        Rule rule = new RuleBuilder(chainId)
            .comingInPort(portId)
            .toIp(matchIp)
            .destNat(new NatTarget(targetIp, 0, 0));
        return prepareCreateRuleFirstPosition(ops, rule);
    }

    public void prepareCreateSourceNatRules(List<Op> ops, UUID inboundChainId,
                                            UUID outboundChainId, UUID portId,
                                            IPv4Addr addr)
            throws SerializationException, StateAccessException,
            RuleIndexOutOfBoundsException {
        Rule sourceNat = new RuleBuilder(outboundChainId)
            .goingOutPort(portId)
            .notSrcIp(addr)
            .sourceNat(new NatTarget(addr));
        Rule dropUnmatched = new RuleBuilder(outboundChainId)
            .isAnyFragmentState()
            .goingOutPort(portId)
            .notSrcIp(addr)
            .drop();

        Rule reverseSourceNat = new RuleBuilder(inboundChainId)
            .hasDestIp(addr)
            .comingInPort(portId)
            .reverseSourceNat();
        Rule dropSourceNat = new RuleBuilder(inboundChainId)
            .notICMP()
            .hasDestIp(addr)
            .drop();

        ArrayList<Rule> outboudRules = new ArrayList<>(2);
        outboudRules.add(sourceNat);
        outboudRules.add(dropUnmatched);
        prepareRulesAppendToEndOfChain(ops, outboundChainId, outboudRules);

        ArrayList<Rule> inboundRules = new ArrayList<>(2);
        inboundRules.add(reverseSourceNat);
        inboundRules.add(dropSourceNat);
        prepareRulesAppendToEndOfChain(ops, inboundChainId, inboundRules);
    }

    public void prepareDeleteSourceNatRules(List<Op> ops, UUID inboundChainId,
                                UUID outboundChainId, IPv4Addr addr, UUID portId)
            throws SerializationException, StateAccessException,
            RuleIndexOutOfBoundsException {

        RuleMatcher reverseSnatMatcher = new ReverseSnatRuleMatcher(addr);
        RuleMatcher dropRuleMatcher = new DefaultDropRuleMatcher(addr);
        List<RuleMatcher> inboundMatchers = new ArrayList<>(2);
        inboundMatchers.add(reverseSnatMatcher);
        inboundMatchers.add(dropRuleMatcher);
        prepareDeleteRules(ops, inboundChainId, inboundMatchers);

        RuleMatcher snatMatcher = new SnatRuleMatcher(new NatTarget(addr));
        RuleMatcher dropUnmatchedMatcher = new DropFragmentRuleMatcher(portId);
        List<RuleMatcher> outboundMatchers = new ArrayList<>(2);
        outboundMatchers.add(snatMatcher);
        outboundMatchers.add(dropUnmatchedMatcher);
        prepareDeleteRules(ops, outboundChainId, outboundMatchers);
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
    public void delete(UUID id) throws StateAccessException, SerializationException {
        zk.multi(prepareDelete(id));
    }
}
