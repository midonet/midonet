package com.midokura.midolman.rules;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.RouterDirectory;

public class RuleEngine {

    private class RouterWatcher implements Runnable {
        @Override
        public void run() {
            try {
                updateChains(true);
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private class RuleChainWatcher implements Runnable {
        String chainName;

        RuleChainWatcher(String chainName) {
            this.chainName = chainName;
        }

        @Override
        public void run() {
            try {
                updateRules(chainName, this);
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private UUID rtrId;
    private RouterDirectory rtrDir;
    private NatMapping natMap;
    protected Map<String, List<Rule>> ruleChains;
    private RouterWatcher rtrWatcher;
    private Set<Runnable> listeners;

    public RuleEngine(RouterDirectory rtrDir, UUID rtrId, NatMapping natMap)
            throws KeeperException, InterruptedException, IOException,
            ClassNotFoundException {
        this.rtrId = rtrId;
        this.rtrDir = rtrDir;
        this.natMap = natMap;
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

    private void updateChains(boolean notify) throws KeeperException,
            InterruptedException, IOException, ClassNotFoundException {
        Collection<String> currentChains = rtrDir.getRuleChainNames(rtrId,
                rtrWatcher);
        Set<String> oldChains = new HashSet<String>(ruleChains.keySet());
        Set<String> newChains = new HashSet<String>(currentChains);
        newChains.removeAll(oldChains);
        oldChains.removeAll(currentChains);

        // Any old chains that are not in currentChains should be removed.
        for (String chain : oldChains) {
            ruleChains.remove(chain);
        }
        // Any brand new chains should be processed.
        for (String chain : newChains) {
            updateRules(chain, new RuleChainWatcher(chain));
        }
        // If no chains were added or deleted we're done. Otherwise, we need to
        // recompute the resources and notify listeners.
        if (oldChains.isEmpty() && newChains.isEmpty())
            return;
        updateResources();
        notifyListeners();
    }

    private void updateRules(String chainName, Runnable watcher)
            throws KeeperException, InterruptedException, IOException,
            ClassNotFoundException {
        List<Rule> curRules = null;
        try {
            curRules = rtrDir.getRuleChain(rtrId, chainName, watcher);
        } catch (KeeperException.NoNodeException e) {
            // The chain has been deleted. The router watcher will handle the
            // notifications.
            return;
        }
        List<Rule> oldRules = ruleChains.get(chainName);
        if (null != oldRules && oldRules.equals(curRules))
            // The chain was updated with the same rules. Do nothing.
            return;

        // Initialize rules that need it.
        for (Rule r : curRules) {
            if (r instanceof NatRule)
                ((NatRule) r).setNatMapping(natMap);
        }
        ruleChains.put(chainName, curRules);

        // If this is a new chain, we're done: the router watcher will handle
        // notifications.
        if (null == oldRules)
            return;
        // The chain already existed. We handle notifications here.
        updateResources();
        notifyListeners();
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
                // Reset the default action and jumpToChain. Keep the
                // transformed match and trackConnection.
                res.action = Action.CONTINUE;
                res.jumpToChain = null;
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
                    chainStack.push(new ChainPosition(res.jumpToChain,
                            nextChain, 0));
                    break;
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
}
