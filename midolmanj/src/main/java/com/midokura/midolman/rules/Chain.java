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

    private class ChainPosition {
        String chainName; // keep this for debugging.
        List<Rule> rules;
        int position;

        public ChainPosition(String chainName, List<Rule> rules, int position) {
            super();
            this.chainName = chainName;
            this.rules = rules;
            this.position = position;
        }
    }

    public RuleResult process(MidoMatch flowMatch, MidoMatch pktMatch,
                              UUID inPortId, UUID outPortId) {

        if (rules.size() == 0) {
            log.debug("applyChain {} - empty, return ACCEPT", chainName);
            return new RuleResult(RuleResult.Action.ACCEPT, null, pktMatch, false);
        }

        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(chainName, rules, 0));
        Set<String> traversedChains = new HashSet<String>();
        traversedChains.add(chainName);

        RuleResult res = new RuleResult(RuleResult.Action.CONTINUE, null,
                pktMatch.clone(), false);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                // Reset the default action and jumpToChain. Keep the
                // transformed match and trackConnection.
                res.action = RuleResult.Action.CONTINUE;
                res.jumpToChain = null;
                cp.rules.get(cp.position).process(flowMatch, inPortId,
                        outPortId, res);
                cp.position++;
                if (res.action.equals(RuleResult.Action.ACCEPT)
                        || res.action.equals(RuleResult.Action.DROP)
                        || res.action.equals(RuleResult.Action.REJECT)) {
                    return res;
                } else if (res.action.equals(RuleResult.Action.JUMP)) {
                    if (traversedChains.contains(res.jumpToChain)) {
                        // Avoid jumping to chains we've already seen.
                        log.warn("applyChain {} cannot jump to chain {} - "
                                + "already visited.", new Object[] { chainName, res.jumpToChain });
                        continue;
                    }

                    Chain nextChain = null; //XXX chains.getChainByName(res.jumpToChain);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        log.warn("ignoring jump to chain {} - not found.", res.jumpToChain);
                        continue;
                    }

                    traversedChains.add(res.jumpToChain);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    chainStack.push(new ChainPosition(res.jumpToChain,
                            nextChain.rules, 0));
                    break;
                } else if (res.action.equals(RuleResult.Action.RETURN)) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                } else if (res.action.equals(RuleResult.Action.CONTINUE)) {
                    // Move on to the next rule in the same chain.
                    continue;
                } else {
                    log.error("Unknown action type {} in rule chain",
                            res.action);
                    // TODO: Should we throw an exception?
                    continue;
                }
            }
        }
        res.action = RuleResult.Action.ACCEPT;
        return res;
    }
}
