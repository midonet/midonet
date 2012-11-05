/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster;

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
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.zkManagers.RuleZkManager;
import com.midokura.midonet.cluster.client.ChainBuilder;

public class ClusterChainManager extends ClusterManager<ChainBuilder> {
    private static final Logger log = LoggerFactory
        .getLogger(ClusterChainManager.class);

    @Inject
    RuleZkManager ruleMgr;

    private Map<UUID, Map<UUID, Rule>> chainIdToRuleMap =
            new HashMap<UUID, Map<UUID, Rule>>();
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
        ruleMgr.getRuleAsync(ruleID, ruleCallback);
    }

    private class RuleListCallback implements DirectoryCallback<Set<UUID>>,
            Directory.TypedWatcher {
        private UUID chainId;

        private RuleListCallback(UUID chainId) {
            this.chainId = chainId;
        }

        @Override
        public void run() {
            log.error("Should NEVER be called");
        }

        @Override
        public void onError(KeeperException e) {
            log.error("Error getting Chain's Rule Id list from cluster");
        }

        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            Set<UUID> curRuleIds = data.getData();
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(chainId);
            // If null, we no longer care about this chainId.
            if (null == ruleMap)
                return;
            Set<UUID> oldRuleIds = ruleMap.keySet();
            Iterator<UUID> ruleIter = oldRuleIds.iterator();
            while (ruleIter.hasNext()) {
                if (!curRuleIds.contains(ruleIter.next()))
                    ruleIter.remove();
            }

            // Are there any rules that need to be added?
            if (oldRuleIds.size() == curRuleIds.size()) {
                getBuilder(chainId).setRules(
                    new HashSet<Rule>(ruleMap.values()));
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
        public void onTimeout() {
            log.error("Timeout getting Chain's Rule Id list from cluster");
        }

        @Override
        public void pathDeleted(String path) {
            // The chain has been deleted.
            log.error("Chain was deleted at {}", path);
            // TODO(pino): Should probably call Builder.delete()
            //getBuilder(chainId).delete();
        }

        @Override
        public void pathCreated(String path) {
            // Should never happen.
            log.error("This shouldn't have been triggered");
        }

        @Override
        public void pathChildrenUpdated(String path) {
            // The list of bgp's for this port has changed. Fetch it again
            // asynchronously.
            ruleMgr.getRuleIdListAsync(chainId, this, this);
        }

        @Override
        public void pathDataChanged(String path) {
            // Our watcher is on the children, so this shouldn't be called.
            // Also, we don't use the data part of this path so it should
            // never change.
            log.error("This shouldn't have been triggered.");
        }

        @Override
        public void pathNoChange(String path) {
            // Do nothing.
        }
    }

    private class RuleCallback implements DirectoryCallback<Rule> {
        private UUID ruleId;

        private RuleCallback(UUID ruleId) {
            this.ruleId = ruleId;
        }

        @Override
        public void onError(KeeperException e) {
            log.error("Error getting Rule from cluster");
        }

        @Override
        public void onSuccess(Result<Rule> data) {
            Rule r = data.getData();
            Collection<UUID> missingRuleIds =
                    chainToMissingRuleIds.get(r.chainId);
            // Does the chain still care about this rule?
            if (!missingRuleIds.contains(ruleId))
                return;
            missingRuleIds.remove(ruleId);
            Map<UUID, Rule> ruleMap = chainIdToRuleMap.get(r.chainId);
            ruleMap.put(ruleId, r);
            if (missingRuleIds.size() == 0)
                getBuilder(r.chainId).setRules(
                    new HashSet<Rule>(ruleMap.values()));

        }

        @Override
        public void onTimeout() {
            log.error("Timeout getting Rule from cluster");
        }
    }
}
