/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.vrn.VRNControllerIface;


public class Chain {
    private final static Logger log = LoggerFactory.getLogger(Chain.class);

    private class RulesWatcher implements Runnable {
        @Override
        public void run() {
            try {
                updateRules();
            } catch (Exception e) {
                log.warn("RulesWatcher.run", e);
            }
        }
    }

    protected UUID chainId;
    private String chainName;
    private RuleZkManager zkRuleManager;
    private VRNControllerIface ctrl;
    private RulesWatcher rulesWatcher = new RulesWatcher();
    protected List<Rule> rules = new ArrayList<Rule>();

    public Chain(UUID chainId, Directory zkDirectory,
                 String zkBasePath, VRNControllerIface ctrl)
            throws StateAccessException {
        this.chainId = chainId;
        ChainZkManager chainMgr = new ChainZkManager(zkDirectory, zkBasePath);
        this.chainName = chainMgr.get(chainId).value.name;
        this.zkRuleManager = new RuleZkManager(zkDirectory, zkBasePath);
        this.ctrl = ctrl;
        updateRules();
    }

    protected Chain () {
        // empty constructor for TestChainProcessor.MockChain
    }

    List<Rule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public String getChainName() {
        return chainName;
    }

    public void setChainName(String chainName) {
        this.chainName = chainName;
    }

    public UUID getID() {
        return chainId;
    }

    protected void updateRules() {

        List<Rule> newRules = new ArrayList<Rule>();

        Set<UUID> ruleIds;
        try {
            ruleIds = zkRuleManager.getRuleIds(chainId, rulesWatcher);
            for (UUID ruleId: ruleIds) {
                newRules.add(zkRuleManager.get(ruleId));
            }
        } catch (StateAccessException e) {
            log.error("updateRules failed", e);
            rules = new ArrayList<Rule>();
            ctrl.invalidateFlowsByElement(chainId);
            return;
        }

        // Rules are unordered in ZooKeeper, so sort by the Rule class's
        // comparator, which orders by the "position" field.
        Collections.sort(newRules);

        if (!rules.equals(newRules)) {
            rules = newRules;
            if (null != ctrl)
                ctrl.invalidateFlowsByElement(chainId);
            // TODO(abel) check nat mappings
        }
    }
}
