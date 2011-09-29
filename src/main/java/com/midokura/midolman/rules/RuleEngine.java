package com.midokura.midolman.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.util.Callback;

public class RuleEngine {

    private final static Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private class RouterWatcher implements Runnable {
        @Override
        public void run() {
            try {
                updateChains(true);
            } catch (Exception e) {
                log.warn("RouteWatcher.run", e);
            }
        }
    }

    private class RuleChainWatcher implements Runnable {
        UUID chainId;

        RuleChainWatcher(UUID chainId) {
            this.chainId = chainId;
        }

        @Override
        public void run() {
            try {
                updateRules(chainId, this);
            } catch (Exception e) {
                log.warn("RuleChainWatcher.run", e);
            }
        }
    }

    private UUID rtrId;
    private String rtrIdStr;
    private ChainZkManager zkChainMgr;
    private RuleZkManager zkRuleMgr;
    private NatMapping natMap;
    protected Map<String, UUID> chainNameToUUID;
    protected Map<UUID, String> chainIdToName;
    protected Map<UUID, List<Rule>> ruleChains;
    private RouterWatcher rtrWatcher;
    private Set<Callback<UUID>> watchers;

    public RuleEngine(ChainZkManager zkChainMgr, RuleZkManager zkRuleMgr,
            UUID rtrId, NatMapping natMap) throws StateAccessException,
            ZkStateSerializationException {
        this.rtrId = rtrId;
        rtrIdStr = rtrId.toString();
        this.zkChainMgr = zkChainMgr;
        this.zkRuleMgr = zkRuleMgr;
        this.natMap = natMap;
        chainNameToUUID = new HashMap<String, UUID>();
        chainIdToName = new HashMap<UUID, String>();
        ruleChains = new HashMap<UUID, List<Rule>>();
        rtrWatcher = new RouterWatcher();
        watchers = new HashSet<Callback<UUID>>();
        updateChains(false);
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        for (Callback<UUID> watcher : watchers)
            // TODO(pino): schedule for later instead of calling them here.
            watcher.call(rtrId);
    }

    private void updateChains(boolean notify) throws StateAccessException,
            ZkStateSerializationException {
        Collection<ZkNodeEntry<UUID, ChainConfig>> entryList = zkChainMgr.list(
                rtrId, rtrWatcher);
        Map<UUID, String> newChainNames = new HashMap<UUID, String>();
        for (ZkNodeEntry<UUID, ChainConfig> entry : entryList) {
            newChainNames.put(entry.key, entry.value.name);
        }
        Set<UUID> currentChains = new HashSet<UUID>(newChainNames.keySet());
        Set<UUID> newChains = new HashSet<UUID>(newChainNames.keySet());
        Set<UUID> oldChains = new HashSet<UUID>(ruleChains.keySet());
        newChains.removeAll(oldChains);
        oldChains.removeAll(currentChains);

        // Any old chains that are not in currentChains should be removed.
        for (UUID chain : oldChains) {
            ruleChains.remove(chain);
            String name = chainIdToName.remove(chain);
            chainNameToUUID.remove(name);
            log.debug("{} removed a chain named {}", rtrIdStr, name);
        }
        // Any brand new chains should be processed.
        for (UUID chain : newChains) {
            updateRules(chain, new RuleChainWatcher(chain));
            String name = newChainNames.get(chain);
            chainIdToName.put(chain, name);
            chainNameToUUID.put(name, chain);
            log.debug("{} added a chain named {}", rtrIdStr, name);
        }
        // If no chains were added or deleted we're done. Otherwise, we need to
        // recompute the resources and notify listeners.
        if (oldChains.isEmpty() && newChains.isEmpty())
            return;
        updateResources();
        notifyWatchers();
    }

    private void updateRules(UUID chainId, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        // TODO(pino): this implementation doesn't use the UUIDs (it preceeded
        // the introduction of the UUIDs) but they make it possible to know
        // exactly which rules changed.
        List<Rule> curRules = new ArrayList<Rule>();
        List<ZkNodeEntry<UUID, Rule>> entries = zkRuleMgr
                .list(chainId, watcher);
        for (ZkNodeEntry<UUID, Rule> entry : entries)
            curRules.add(entry.value);
        Collections.sort(curRules);

        List<Rule> oldRules = ruleChains.get(chainId);
        if (null != oldRules && oldRules.equals(curRules))
            // The chain was updated with the same rules. Do nothing.
            return;
        log.debug(
                "{} updating chain {}. Old length {}, new length {}.",
                new Object[] { rtrIdStr, chainIdToName.get(chainId),
                        null == oldRules ? 0 : oldRules.size(), curRules.size() });

        // Initialize rules that need it.
        for (Rule r : curRules) {
            if (r instanceof NatRule)
                ((NatRule) r).setNatMapping(natMap);
        }
        ruleChains.put(chainId, curRules);

        // If this is a new chain, we're done: the router watcher will handle
        // notifications.
        if (null == oldRules)
            return;
        // The chain already existed. We handle notifications here.
        updateResources();
        notifyWatchers();
    }

    private void updateResources() {
        // Tell the NatMapping about all the current NatTargets for SNAT.
        // TODO(pino): the NatMapping should clean up any old targets that
        // are no longer used and remember the current targets.
        Set<NatTarget> targets = new HashSet<NatTarget>();
        for (List<Rule> chain : ruleChains.values()) {
            for (Rule r : chain) {
                if (r instanceof ForwardNatRule) {
                    ForwardNatRule fR = (ForwardNatRule) r;
                    if (!fR.dnat)
                        targets.addAll(fR.getNatTargets());
                }
            }
        }
        natMap.updateSnatTargets(targets);
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

    /**
     * 
     * @param chainName
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param pktMatch
     *            matches the packet that would be seen in thir router/chain. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @return
     */
    public RuleResult applyChain(String chainName, MidoMatch flowMatch,
            MidoMatch pktMatch, UUID inPortId, UUID outPortId) {
        List<Rule> chain = null;
        UUID chainId = chainNameToUUID.get(chainName);
        if (null != chainId)
            chain = ruleChains.get(chainId);
        if (null == chain || chain.size() == 0) {
            log.debug("applyChain {} - empty, return ACCEPT", chainName);
            return new RuleResult(Action.ACCEPT, null, pktMatch, false);
        }

        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(chainName, chain, 0));
        Set<String> traversedChains = new HashSet<String>();
        traversedChains.add(chainName);

        RuleResult res = new RuleResult(Action.CONTINUE, null,
                pktMatch.clone(), false);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                // Reset the default action and jumpToChain. Keep the
                // transformed match and trackConnection.
                res.action = Action.CONTINUE;
                res.jumpToChain = null;
                cp.rules.get(cp.position).process(flowMatch, inPortId,
                        outPortId, res);
                cp.position++;
                if (res.action.equals(Action.ACCEPT)
                        || res.action.equals(Action.DROP)
                        || res.action.equals(Action.REJECT)) {
                    return res;
                } else if (res.action.equals(Action.JUMP)) {
                    if (traversedChains.contains(res.jumpToChain)) {
                        // Avoid jumping to chains we've already seen.
                        log.warn("{} applyChain {} cannot jump to chain {} - "
                                + "already visited.", new Object[] { rtrIdStr,
                                chainName, res.jumpToChain });
                        continue;
                    }
                    List<Rule> nextChain = null;
                    chainId = chainNameToUUID.get(res.jumpToChain);
                    if (null != chainId)
                        nextChain = ruleChains.get(chainId);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        log.warn("{} ignoring jump to chain {} - not found.",
                                rtrIdStr, res.jumpToChain);
                        continue;
                    }
                    traversedChains.add(res.jumpToChain);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    chainStack.push(new ChainPosition(res.jumpToChain,
                            nextChain, 0));
                    break;
                } else if (res.action.equals(Action.RETURN)) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                } else if (res.action.equals(Action.CONTINUE)) {
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
        res.action = Action.ACCEPT;
        return res;
    }

    public void onFlowRemoved(OFMatch match) {
        natMap.onFlowRemoved(match);
    }
}
