/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.cluster.client.ChainBuilder;

public class ClusterChainManager extends ClusterManager<ChainBuilder> {
    private static final Logger log = LoggerFactory
        .getLogger(ClusterChainManager.class);

    @Inject
    RuleZkManager ruleMgr;

    private Map<UUID, Map<UUID, Rule>> chainIdToRuleMap =
            new HashMap<UUID, Map<UUID, Rule>>();
    private Map<UUID, Set<UUID>> chainToRuleIds =
            new HashMap<UUID, Set<UUID>>();
    private Multimap<UUID, UUID> chainToMissingRuleIds =
            HashMultimap.create();

    @Override
    protected void getConfig(UUID chainId) {
        if (chainIdToRuleMap.containsKey(chainId)) {
            log.error("Trying to request the same Chain {}.", chainId);
            return;
        }
        chainIdToRuleMap.put(chainId, new HashMap<UUID, Rule>());
        RuleListCallback ruleListCB = new RuleListCallback(chainId);
        ruleMgr.getRuleIdListAsync(chainId, ruleListCB, ruleListCB);
    }

    private void requestRule(UUID ruleID) {
        RuleCallback ruleCallback = new RuleCallback(ruleID);
        ruleMgr.getRuleAsync(ruleID, ruleCallback, ruleCallback);
    }

    private boolean isChainConsistent(Collection<Rule> rules) {
        Set<Integer> positions = new HashSet<Integer>();
        for (Rule r: rules) {
            if (r.position > rules.size())
                return false;
            else
                positions.add(r.position);
        }
        return (positions.size() == rules.size());
    }

    private class RuleListCallback extends CallbackWithWatcher<Set<UUID>> {
        private UUID chainId;

        private RuleListCallback(UUID chainId) {
            this.chainId = chainId;
        }

        @Override
        protected String describe() {
            return "RuleList:" + chainId;
        }

        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            Set<UUID> curRuleIds = data.getData();
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(chainId);
            // If null, we no longer care about this chainId.
            if (null == ruleMap) {
                chainToRuleIds.remove(chainId);
                return;
            } else {
                chainToRuleIds.put(chainId, curRuleIds);
            }
            Set<UUID> oldRuleIds = ruleMap.keySet();
            Iterator<UUID> ruleIter = oldRuleIds.iterator();
            while (ruleIter.hasNext()) {
                if (!curRuleIds.contains(ruleIter.next()))
                    ruleIter.remove();
            }

            // Are there any rules that need to be added?
            if (oldRuleIds.size() == curRuleIds.size() &&
                isChainConsistent(ruleMap.values())) {

                getBuilder(chainId).setRules(new HashSet<Rule>(ruleMap.values()));
                return;
            }
            // Otherwise, we have to fetch some rules.
            for (UUID ruleId : curRuleIds) {
                if (!oldRuleIds.contains(ruleId)) {
                    chainToMissingRuleIds.put(chainId, ruleId);
                    requestRule(ruleId);
                }
            }
        }

        @Override
        public void pathChildrenUpdated(String path) {
            // The list of bgp's for this port has changed. Fetch it again
            // asynchronously.
            ruleMgr.getRuleIdListAsync(chainId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    ruleMgr.getRuleIdListAsync(chainId,
                            RuleListCallback.this, RuleListCallback.this);
                }
            };
        }
    }

    private class RuleCallback extends CallbackWithWatcher<Rule> {
        private UUID ruleId;

        private RuleCallback(UUID ruleId) {
            this.ruleId = ruleId;
        }

        @Override
        protected String describe() {
            return "Rule:" + ruleId;
        }

        @Override
        public void onSuccess(Result<Rule> data) {
            Rule rule = data.getData();
            Collection<UUID> missingRuleIds =
                    chainToMissingRuleIds.get(rule.chainId);
            Set<UUID> ruleIds = chainToRuleIds.get(rule.chainId);
            // Does the chain still care about this rule?
            if (ruleIds == null || ! ruleIds.contains(ruleId))
                return;
            missingRuleIds.remove(ruleId);
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(rule.chainId);

            ruleMap.put(ruleId, rule);

            if ((missingRuleIds.size() == 0) && isChainConsistent(ruleMap.values())) {
                getBuilder(rule.chainId).setRules(
                        new HashSet<Rule>(ruleMap.values()));
            }
        }

        @Override
        public void pathDataChanged(String path) {
            ruleMgr.getRuleAsync(ruleId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    ruleMgr.getRuleAsync(ruleId,
                        RuleCallback.this, RuleCallback.this);
                }
            };
        }
    }
}
