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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

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
