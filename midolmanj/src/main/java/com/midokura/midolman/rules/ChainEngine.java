// Copyright 2012 Midokura Inc.

package com.midokura.midolman.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.StateAccessException;
import com.midokura.sdn.flows.PacketMatch;


public class ChainEngine {
    private ChainGetter chainGetter;
    private final static Logger log =
        LoggerFactory.getLogger(ChainEngine.class);

    public ChainEngine(ChainGetter chainGetter_) {
        chainGetter = chainGetter_;
    }

    /**
     * @param chainID
     * @param fwdInfo
     *            The packet's PacketContext.
     * @param pktMatch
     *            matches the packet that would be seen in this router/chain. It
     *            will NOT be modified by the rule chain.
     * @param ownerId
     *            UUID of the element using chainId.
     * @param isPortFilter
     *            whether the chain is being processed in a port filter context
     * @return
     */
    public RuleResult applyChain(UUID chainID, ChainPacketContext fwdInfo,
                                 PacketMatch pktMatch, UUID ownerId,
                                 boolean isPortFilter)
            throws StateAccessException {
        RuleResult res = new RuleResult(
                RuleResult.Action.ACCEPT, null, pktMatch, false);
        if (null == chainID) {
             return res;
        }
        Chain currentChain = chainGetter.getOrCreateChain(chainID);
        fwdInfo.addTraversedElementID(chainID);
        if (null == currentChain) {
            log.warn("Could not find a Chain corresponding to ID {}", chainID);
            return res;
        }
        log.debug("Processing chain with name {} and ID {}",
                currentChain.getChainName(), chainID);
        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(chainID, currentChain.getRules(), 0));
        // We can't use traversedElementIDs to detect loops because the same
        // chains may have been traversed by some other device's filters.
        Set<UUID> traversedChains = new HashSet<UUID>();
        traversedChains.add(chainID);

        res = new RuleResult(RuleResult.Action.CONTINUE, null,
                pktMatch.clone(), false);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                // Reset the default action and jumpToChain. Keep the
                // transformed match and trackConnection.
                res.action = RuleResult.Action.CONTINUE;
                res.jumpToChain = null;
                cp.rules.get(cp.position).process(fwdInfo, res,
                                                  chainGetter.getNatMapping(
                                                        ownerId),
                                                  isPortFilter);
                cp.position++;
                if (res.action.equals(RuleResult.Action.ACCEPT)
                        || res.action.equals(RuleResult.Action.DROP)
                        || res.action.equals(RuleResult.Action.REJECT)) {
                    return res;
                } else if (res.action.equals(RuleResult.Action.JUMP)) {
                    Chain nextChain = chainGetter.getOrCreateChain(
                                            res.jumpToChain);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        log.warn("ignoring jump to chain {} -- not found.",
                                 res.jumpToChain);
                        continue;
                    }

                    UUID nextID = nextChain.getID();
                    if (traversedChains.contains(nextID)) {
                        // Avoid jumping to chains we've already seen.
                        log.warn("applyChain {}({}) cannot jump from chain " +
                                 " {}({}) to chain {}({}) -- already visited",
                                new Object[] {chainID,
                                chainGetter.getOrCreateChain(chainID)
                                           .getChainName(),
                                cp.id,
                                chainGetter.getOrCreateChain(cp.id)
                                           .getChainName(),
                                res.jumpToChain,
                                nextChain.getChainName()});
                        continue;
                    }
                    // Keep track of the traversed chains to detect loops.
                    traversedChains.add(nextID);
                    // The flow may be invalidated if any traversed id changes.
                    fwdInfo.addTraversedElementID(nextID);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    chainStack.push(
                        new ChainPosition(nextID, nextChain.getRules(), 0));
                    break;
                } else if (res.action.equals(RuleResult.Action.RETURN)) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                } else if (res.action.equals(RuleResult.Action.CONTINUE)) {
                    // Move on to the next rule in the same chain.
                    continue;
                } else {
                    log.error("Unknown action type {} in rule chain {}",
                              res.action, cp.id);
                    // TODO: Should we throw an exception?
                    continue;
                }
            }
        }
        // If we fall off the end of the starting chain, we ACCEPT.
        res.action = RuleResult.Action.ACCEPT;
        return res;
    }

    public void freeFlowResources(OFMatch match, UUID ownerID) {
        chainGetter.freeFlowResources(match, ownerID);
    }

    private class ChainPosition {
        UUID id;
        List<Rule> rules;
        int position;

        public ChainPosition(UUID id, List<Rule> rules, int position) {
            this.id = id;
            this.rules = rules;
            this.position = position;
        }
    }
}
