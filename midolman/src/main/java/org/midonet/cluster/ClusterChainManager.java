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

package org.midonet.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.ChainBuilder;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;

public class ClusterChainManager extends ClusterManager<ChainBuilder> {
    private static final Logger log =
        LoggerFactory.getLogger(ClusterChainManager.class);

    @Inject
    RuleZkManager ruleMgr;

    @Inject
    ChainZkManager chainMgr;

    private Map<UUID, Map<UUID, Rule>> chainIdToRuleMap = new HashMap<>();
    private Map<UUID, List<UUID>> chainToRuleIds = new HashMap<>();
    private Multimap<UUID,UUID> chainToMissingRuleIds = HashMultimap.create();

    @Override
    protected void getConfig(UUID chainId) {
        if (chainIdToRuleMap.containsKey(chainId)) {
            log.error("Trying to request the same Chain {}.", chainId);
            return;
        }
        chainIdToRuleMap.put(chainId, new HashMap<UUID,Rule>());
        ChainNameCallback nameCB = new ChainNameCallback(chainId);
        chainMgr.getNameAsync(chainId, nameCB, nameCB);
        RuleListCallback ruleListCB = new RuleListCallback(chainId);
        ruleMgr.getRuleIdListAsync(chainId, ruleListCB, ruleListCB);
    }

    private void requestRule(UUID ruleID) {
        RuleCallback ruleCallback = new RuleCallback(ruleID);
        ruleMgr.getAsync(ruleID, ruleCallback, ruleCallback);
    }

    private class ChainNameCallback extends CallbackWithWatcher<String> {
        private UUID chainId;

        private ChainNameCallback(UUID chainId) {
            this.chainId = chainId;
        }

        @Override
        protected String describe() {
            return "ChainName:" + chainId;
        }

        @Override
        public void onSuccess(String data) {
            getBuilder(chainId).setName(data);
        }

        @Override
        public void pathDataChanged(String path) {
            chainMgr.getNameAsync(chainId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() { }
            };
        }
    }

    private class RuleListCallback extends CallbackWithWatcher<List<UUID>> {
        private UUID chainId;

        private RuleListCallback(UUID chainId) {
            this.chainId = chainId;
        }

        @Override
        protected String describe() {
            return "RuleList:" + chainId;
        }

        @Override
        public void onSuccess(List<UUID> curRuleIds) {
            // curlRuleIds is an ordered list of the UUIDs of current rules

            // UUID to actual rule for each rule in chain
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(chainId);

            // If null, we no longer care about this chainId.
            if (null == ruleMap) {
                chainToRuleIds.remove(chainId);
                return;
            } else {
                chainToRuleIds.put(chainId, curRuleIds);
            }

            // List of old rule IDs from chain
            Set<UUID> oldRuleIds = ruleMap.keySet();

            // If the new ordered list tells us a rule disappeared,
            // remove it from the chain's rule id -> rule info map
            Iterator<UUID> ruleIter = oldRuleIds.iterator();
            while (ruleIter.hasNext()) {
                if (!curRuleIds.contains(ruleIter.next()))
                    ruleIter.remove();
            }

            // If we have all the rules in the new ordered list, we're
            // ready to call the chainbuilder
            if (oldRuleIds.size() == curRuleIds.size()) {
                getBuilder(chainId).setRules(curRuleIds, ruleMap);
                return;
            }
            // Otherwise, we have to fetch some rules.
            for (UUID ruleId : curRuleIds) {
                if (!oldRuleIds.contains(ruleId)) {
                    chainToMissingRuleIds.put(chainId, ruleId);
                }
            }
            /* NOTE(guillermo) this loop is split in two because, unit tests
             * will have the rule callback run synchronously with requestRule(),
             * meaning that the rule callback will not be serialized vs this
             * callback, causing it to run on an inconsistent state.
             */
            for (UUID ruleId : curRuleIds) {
                if (!ruleMap.containsKey(ruleId))
                    requestRule(ruleId);
            }
        }

        @Override
        public void pathDataChanged(String path) {
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
        public void onSuccess(Rule rule) {
            Collection<UUID> missingRuleIds =
                    chainToMissingRuleIds.get(rule.chainId);
            List<UUID> ruleIds = chainToRuleIds.get(rule.chainId);
            // Does the chain still care about this rule?
            if (ruleIds == null || ! ruleIds.contains(ruleId))
                return;
            missingRuleIds.remove(ruleId);
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(rule.chainId);

            ruleMap.put(ruleId, rule);

            if ((missingRuleIds.isEmpty())) {
                getBuilder(rule.chainId).setRules(ruleIds, ruleMap);
            }
        }

        @Override
        public void pathDataChanged(String path) {
            ruleMgr.getAsync(ruleId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    ruleMgr.getAsync(ruleId,
                        RuleCallback.this, RuleCallback.this);
                }
            };
        }
    }
}
