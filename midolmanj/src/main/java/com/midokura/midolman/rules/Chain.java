/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.rules;

import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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
    protected List<Rule> rules;
    private RulesWatcher rulesWatcher;
    private RuleZkManager zkRuleManager;

    public Chain(UUID chainId, Directory zkDirectory,
                 String zkBasePath) throws StateAccessException {
        this.chainId = chainId;
        ChainZkManager chainMgr = new ChainZkManager(zkDirectory, zkBasePath);
        this.chainName = chainMgr.get(chainId).value.name;
        this.rules = new LinkedList<Rule>();
        this.rulesWatcher = new RulesWatcher();
        this.zkRuleManager = new RuleZkManager(zkDirectory, zkBasePath);
        updateRules();
    }

    protected Chain () {
        // empty constructor for mock class
    }

    List<Rule> getRules() {
        return rules;
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

    public void updateRules() {

        List<Rule> newRules = new ArrayList<Rule>();

        List<ZkNodeEntry<UUID, Rule>> entries =
                null;
        try {
            entries = zkRuleManager.list(chainId, rulesWatcher);
        } catch (StateAccessException e) {
            log.error("updateRules failed", e);
            rules = new ArrayList<Rule>();
            return;
        }
        for (ZkNodeEntry<UUID, Rule> entry : entries) {
            newRules.add(entry.value);
        }
        // Rules are unordered in ZooKeeper, so sort by the Rule class's
        // comparator, which orders by the "position" field.
        Collections.sort(newRules);

        rules = newRules;

        // TODO(abel) check nat mappings

        /*
        updateResources();
        notifyWatchers();
        */
    }

}
