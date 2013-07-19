// Copyright 2012 Midokura Inc.

package org.midonet.midolman.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import akka.event.LoggingBus;
import com.google.inject.Inject;
import scala.Option;
import scala.Some;
import scala.collection.Map;

import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.midolman.layer4.NatMappingFactory;
import org.midonet.midolman.logging.LoggerFactory;
import org.midonet.midolman.logging.SimulationAwareBusLogging;
import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.topology.FlowTagger;

public class Chain {
    private SimulationAwareBusLogging log;

    public UUID id;
    private List<Rule> rules;
    private Map<UUID, Chain> jumpTargets;
    private String name;

    @Inject
    private static NatMappingFactory natMappingFactory;

    public Chain(UUID id_, List<Rule> rules_, Map<UUID, Chain> jumpTargets_,
                 String name_, LoggingBus loggingBus) {
        id = id_;
        rules = new ArrayList<Rule>(rules_);
        jumpTargets = jumpTargets_;
        name = name_;
        log = LoggerFactory.getSimulationAwareLog(this.getClass(),
                                                  loggingBus);
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

    private SimulationAwareBusLogging getLog() {
        return log;
    }

    /**
     * @param origChain
     *            The chain where processing starts.
     * @param fwdInfo
     *            The packet's PacketContext.
     * @param pktMatch
     *            matches the packet that would be seen in this router/chain. It
     *            WILL be modified by the rule chain if it affects matches.
     * @param ownerId
     *            UUID of the element using chainId.
     * @param isPortFilter
     *            whether the chain is being processed in a port filter context
     * @return
     */
    public static RuleResult apply(
            Chain origChain, ChainPacketContext fwdInfo,
            WildcardMatch pktMatch, UUID ownerId, boolean isPortFilter) {
        if (null == origChain) {
             return new RuleResult(
                RuleResult.Action.ACCEPT, null, pktMatch);
        }
        Chain currentChain = origChain;
        fwdInfo.addTraversedElementID(origChain.id);
        fwdInfo.addFlowTag(FlowTagger.invalidateFlowsByDevice(currentChain.id));
        fwdInfo.addFlowTag(
            FlowTagger.invalidateFlowsByDeviceFilter(ownerId, currentChain.id));
        currentChain.getLog().debug("Processing chain with name {} and ID {}",
                                    currentChain.name, currentChain.id, fwdInfo);
        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(currentChain.id,
                                          currentChain.rules, 0));
        // We can't use traversedElementIDs to detect loops because the same
        // chains may have been traversed by some other device's filters.
        Set<UUID> traversedChains = new HashSet<UUID>();
        traversedChains.add(currentChain.id);

        RuleResult res = new RuleResult(RuleResult.Action.CONTINUE, null,
                pktMatch);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                // Reset the default action and jumpToChain. Keep the
                // transformed match.
                res.action = RuleResult.Action.CONTINUE;
                res.jumpToChain = null;
                Rule r = cp.rules.get(cp.position);
                cp.position++;
                r.process(fwdInfo, res, natMappingFactory.get(ownerId), isPortFilter);
                if (res.action.equals(RuleResult.Action.ACCEPT)
                        || res.action.equals(RuleResult.Action.DROP)
                        || res.action.equals(RuleResult.Action.REJECT)) {
                    return res;
                } else if (res.action.equals(RuleResult.Action.JUMP)) {
                    Chain nextChain = currentChain.getJumpTarget(
                                            res.jumpToChain);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        currentChain.getLog().warning(
                            "ignoring jump to chain {} -- not found.",
                            res.jumpToChain, fwdInfo);
                        continue;
                    }

                    UUID nextID = nextChain.id;
                    if (traversedChains.contains(nextID)) {
                        // Avoid jumping to chains we've already seen.
                        currentChain.getLog().warning(
                            "applyChain {} cannot jump from chain " +
                                "{} to chain {} -- already visited",
                            new Object[]{origChain, currentChain,
                                nextChain}, fwdInfo);
                        continue;
                    }
                    // Keep track of the traversed chains to detect loops.
                    traversedChains.add(nextID);
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
                    currentChain.getLog().error(
                        "Unknown action type {} in rule chain {}",
                        res.action, cp.id, fwdInfo);
                    // TODO: Should we throw an exception?
                    continue;
                }
            }
        }
        // If we fall off the end of the starting chain, we ACCEPT.
        res.action = RuleResult.Action.ACCEPT;
        return res;
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
