package com.midokura.midolman.rules;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.RouterDirectory;

public class RuleEngine {

    private class RouterWatcher implements Runnable {
        @Override
        public void run() {
            updateChains(true);
        }
    }

    private class RuleChainWatcher implements Runnable {
        String chainName;

        RuleChainWatcher(String chainName) {
            this.chainName = chainName;
        }

        @Override
        public void run() {
            updateRules(chainName, this, true);
        }
    }

    private Map<String, List<Rule>> ruleChains;
    private UUID rtrId;
    private RouterDirectory rtrDir;
    private RouterWatcher rtrWatcher;
    private Set<Runnable> listeners;

    public RuleEngine(RouterDirectory rtrDir, UUID rtrId) {
        this.rtrId = rtrId;
        this.rtrDir = rtrDir;
        ruleChains = new HashMap<String, List<Rule>>();
        rtrWatcher = new RouterWatcher();
        listeners = new HashSet<Runnable>();
        updateChains(false);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners)
            // TODO(pino): schedule for later instead of calling them here.
            listener.run();
    }

    private void updateChains(boolean notify) {
        Collection<String> currentChains = 
                rtrDir.getRuleChainNames(rtrId, rtrWatcher);
        Set<String> deletedChains = new HashSet<String>(ruleChains.keySet());
        deletedChains.removeAll(currentChains);
        boolean changed = !deletedChains.isEmpty();
        for (String chain : deletedChains) {
            List<Rule> rules = ruleChains.remove(chain);
            for (Rule r : rules)
                ;//TODO(pino): free the rule's resources?
        }
        for (String chain : currentChains) {
            if (ruleChains.containsKey(chain))
                continue;
            changed = true;
            updateRules(chain, new RuleChainWatcher(chain), false);
        }
        if (changed && notify)
            notifyListeners();
    }

    private void updateRules(String chainName, Runnable watcher, 
            boolean notify) {
        List<Rule> curRules = null;
        try {
            curRules = rtrDir.getRuleChain(rtrId, chainName, watcher);
        }
        catch (KeeperException.NoNodeException e) {
            // The chain has  been deleted. The router watcher will handle this.
            return;
        }
        List<Rule> oldRules = ruleChains.put(chainName, curRules);
        boolean changed = false;
        if (null == oldRules) {
            // Since we didn't know about this chain before, no one could have
            // been interested in changes to it.
            return;
        }
        else {
            // For any rule that already existed, use the old rule since it
            // may own resources (it's already initialized).
            for (int i=0; i<curRules.size(); i++) {
                Rule r = curRules.get(i);
                int oldIndex = oldRules.indexOf(r);
                if (-1 == oldIndex) {
                    ; //r.init(/* TODO(pino): pass the router.*/);
                }
                else {
                    curRules.set(i, oldRules.get(oldIndex));
                }
            }
        }
        if (changed && notify)
            notifyListeners();
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

    public RuleResult applyChain(String chainName, MidoMatch pktMatch,
            UUID inPortId, UUID outPortId) {
        List<Rule> chain = ruleChains.get(chainName);
        if (null == chain || chain.size() == 0)
            return new RuleResult(Action.ACCEPT, null, pktMatch, false);

        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(chainName, chain, 0));
        Set<String> traversedChains = new HashSet<String>();
        traversedChains.add(chainName);

        RuleResult res = new RuleResult(Action.CONTINUE, null, pktMatch, false);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                cp.rules.get(cp.position).process(inPortId, outPortId, res);
                cp.position++;
                if (res.action == Action.ACCEPT || res.action == Action.DROP
                        || res.action == Action.REJECT) {
                    return res;
                } else if (res.action == Action.JUMP) {
                    if (traversedChains.contains(res.jumpToChain)) {
                        // TODO(pino): log a warning?
                        // Avoid jumping to chains we've already seen.
                        continue;
                    }
                    List<Rule> nextChain = ruleChains.get(res.jumpToChain);
                    if (null == nextChain) {
                        // TODO(pino): should we throw an exception?
                        // Let's just ignore jumps to non-existent chains.
                        continue;
                    }
                    traversedChains.add(res.jumpToChain);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    // Start processing in the new chain.
                    cp = new ChainPosition(res.jumpToChain, nextChain, 0);
                    continue;
                } else if (res.action == Action.RETURN) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                } else { // Action.CONTINUE
                         // TODO(pino): should we check that action == CONTINUE?
                         // Move on to the next rule in the same chain.
                    continue;
                }
            }
        }
        res.action = Action.ACCEPT;
        return res;
    }

    public RuleEngine(Map<String, List<Rule>> ruleChains) {
        super();
        this.ruleChains = ruleChains;
    }

}
