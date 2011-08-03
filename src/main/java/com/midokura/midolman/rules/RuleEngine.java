package com.midokura.midolman.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;

public class RuleEngine {

    private Map<String, List<Rule>> ruleChains;

    public RuleEngine() {

    }

    public void setChainRules(String chainName, List<Rule> rules) {
        
    }

    private class ChainPosition {
        String chainName;  // keep this for debugging.
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
        while(!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                cp.rules.get(cp.position).process(inPortId, outPortId, res);
                cp.position++;
                if (res.action == Action.ACCEPT || 
                        res.action == Action.DROP ||
                        res.action == Action.REJECT) {
                    return res;
                }
                else if (res.action == Action.JUMP) {
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
                }
                else if (res.action == Action.RETURN) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                }
                else { //Action.CONTINUE
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
