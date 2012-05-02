/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.rules;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
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

    private UUID chainId;
    private String chainName;
    private List<Rule> rules;
    private RulesWatcher rulesWatcher;
    private RuleZkManager zkRuleManager;

    public Chain(UUID chainId, String chainName, Directory zkDirectory,
                 String zkBasePath) {
        this.chainId = chainId;
        this.chainName = chainName;
        this.rules = new LinkedList<Rule>();
        this.rulesWatcher = new RulesWatcher();
        this.zkRuleManager = new RuleZkManager(zkDirectory, zkBasePath);
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

    public void updateRules()
            throws StateAccessException, ZkStateSerializationException {

        List<Rule> newRules = new ArrayList<Rule>();

        List<ZkNodeEntry<UUID, Rule>> entries =
            zkRuleManager.list(chainId, rulesWatcher);
        for (ZkNodeEntry<UUID, Rule> entry : entries) {
            newRules.add(entry.value);
        }
        Collections.sort(newRules);

        rules = newRules;

        // TODO(abel) check nat mappings

        /*
        updateResources();
        notifyWatchers();
        */
    }

}
