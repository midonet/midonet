// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import scala.collection.Map;
import scala.Option;
import scala.Some;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.rules.ChainPacketContext;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.sdn.flows.PacketMatch;


public class Chain {
    private final static Logger log = LoggerFactory.getLogger(Chain.class);

    public UUID id;
    private List<Rule> rules;
    private Map<UUID, Chain> jumpTargets;
    private String name;

    public Chain(UUID id_, List<Rule> rules_, Map<UUID, Chain> jumpTargets_,
                 String name_) {
        id = id_;
        rules = rules_;
        jumpTargets = jumpTargets_;
        name = name_;
    }

    public int hashCode() {
        return id.hashCode();
    }

    public boolean equals(Object other) {
        if (!(other instanceof Chain))
            return false;
        Chain that = (Chain) other;
        return this.id.equals(that.id) && this.rules.equals(that.rules) &&
                this.jumpTargets.equals(that.jumpTargets) &&
                this.name.equals(that.name);
    }

    public Chain getJumpTarget(UUID to) {
        Option<Chain> match = jumpTargets.get(to);
        if (match.isDefined())
            return ((Some<Chain>) match).get();
        else
            return null;
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
    public static RuleResult apply(
            Chain origChain, ChainPacketContext fwdInfo,
            PacketMatch pktMatch, UUID ownerId, boolean isPortFilter) {
        RuleResult res = new RuleResult(
                RuleResult.Action.ACCEPT, null, pktMatch, false);
        if (null == origChain) {
             return res;
        }
        Chain currentChain = origChain;
        fwdInfo.addTraversedElementID(origChain.id);
        log.debug("Processing chain with name {} and ID {}",
                currentChain.name, currentChain.id);
        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(currentChain.id,
                                          currentChain.rules, 0));
        // We can't use traversedElementIDs to detect loops because the same
        // chains may have been traversed by some other device's filters.
        Set<UUID> traversedChains = new HashSet<UUID>();
        traversedChains.add(currentChain.id);

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
                                                  getNatMapping(ownerId),
                                                  isPortFilter);
                cp.position++;
                if (res.action.equals(RuleResult.Action.ACCEPT)
                        || res.action.equals(RuleResult.Action.DROP)
                        || res.action.equals(RuleResult.Action.REJECT)) {
                    return res;
                } else if (res.action.equals(RuleResult.Action.JUMP)) {
                    Chain nextChain = currentChain.getJumpTarget(
                                            res.jumpToChain);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        log.warn("ignoring jump to chain {} -- not found.",
                                 res.jumpToChain);
                        continue;
                    }

                    UUID nextID = nextChain.id;
                    if (traversedChains.contains(nextID)) {
                        // Avoid jumping to chains we've already seen.
                        log.warn("applyChain {} cannot jump from chain " +
                                 " {} to chain {} -- already visited",
                                new Object[] { origChain, currentChain,
                                               nextChain });
                        continue;
                    }
                    // Keep track of the traversed chains to detect loops.
                    traversedChains.add(nextID);
                    // The flow may be invalidated if any traversed id changes.
                    fwdInfo.addTraversedElementID(nextID);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    chainStack.push(
                        new ChainPosition(nextID, nextChain.rules, 0));
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

    private static NatMapping getNatMapping(UUID ownerID) {
        return null;  //XXX
    }

    private static class ChainPosition {
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
