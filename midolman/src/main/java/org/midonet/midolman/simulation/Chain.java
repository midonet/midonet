/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import akka.event.LoggingBus;
import com.google.inject.Inject;
import scala.Option;
import scala.collection.Map;

import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.midolman.layer4.NatMappingFactory;
import org.midonet.midolman.logging.LoggerFactory;
import org.midonet.midolman.logging.SimulationAwareBusLogging;
import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.sdn.flows.FlowTagger;

public class Chain {
    private SimulationAwareBusLogging log;

    public final UUID id;
    private final List<Rule> rules;
    private final Map<UUID, Chain> jumpTargets;
    public final String name;
    public final FlowTagger.FlowTag flowInvTag;

    @Inject
    static NatMappingFactory natMappingFactory;

    public Chain(UUID id_, List<Rule> rules_, Map<UUID, Chain> jumpTargets_,
                 String name_, LoggingBus loggingBus) {
        id = id_;
        rules = new ArrayList<>(rules_);
        jumpTargets = jumpTargets_;
        name = name_;
        flowInvTag = FlowTagger.tagForDevice(id);
        log = LoggerFactory.getSimulationAwareLog(this.getClass(),
                                                  loggingBus);
    }

    public int hashCode() {
        return id.hashCode();
    }

    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (other == null || getClass() != other.getClass())
            return false;
        Chain that = (Chain) other;
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.rules, that.rules) &&
                Objects.equals(this.jumpTargets, that.jumpTargets) &&
                Objects.equals(this.name, that.name);
    }

    public Chain getJumpTarget(UUID to) {
        Option<Chain> match = jumpTargets.get(to);
        return match.isDefined() ? match.get() : null;
    }

    /**
     * Recursive helper function for public static apply(). The first
     * three parameters are the same as in that method.
     *
     * @param res
     *     Results of rule processing (out only), plus packet match (in/out).
     * @param depth
     *     Depth of jump recursion. Guards against excessive recursion.
     * @param traversedChains
     *     Keeps track of chains that have been visited to prevent
     *     infinite recursion in the event of a cycle.
     */
    private void apply(ChainPacketContext fwdInfo, UUID ownerId,
                       boolean isPortFilter, RuleResult res,
                       int depth, List<UUID> traversedChains) {

        log.debug("Processing chain with name {} and ID {}", name, id, fwdInfo);
        if (depth > 10) {
            throw new IllegalStateException("Deep recursion when processing " +
                                            "chain " + traversedChains.get(0));
        }

        fwdInfo.addFlowTag(flowInvTag);
        traversedChains.add(id);

        Iterator<Rule> iter = rules.iterator();
        res.action = Action.CONTINUE;
        while (iter.hasNext() && res.action == Action.CONTINUE) {

            Rule r = iter.next();
            r.process(fwdInfo, res, natMappingFactory.get(ownerId), isPortFilter);

            if (res.action == Action.JUMP) {
                Chain jumpChain = getJumpTarget(res.jumpToChain);
                if (null == jumpChain) {
                    log.error("ignoring jump to chain {} -- not found.",
                              res.jumpToChain, fwdInfo);
                    res.action = Action.CONTINUE;
                } else if (traversedChains.contains(jumpChain.id)) {
                    log.warning("Chain.apply cannot jump from chain " +
                                "{} to chain {} -- already visited",
                                this, jumpChain, fwdInfo);
                    res.action = Action.CONTINUE;
                } else {
                    // Apply the jump chain and return if it produces a
                    // decisive action. If not, on to the next rule.
                    jumpChain.apply(fwdInfo, ownerId, isPortFilter,
                                    res, depth + 1, traversedChains);
                    if (res.action == Action.RETURN)
                        res.action = Action.CONTINUE;
                }
            }
        }

        assert res.action != Action.JUMP;
    }

    /**
     * @param chain
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
     */
    public static RuleResult apply(
            Chain chain, ChainPacketContext fwdInfo,
            WildcardMatch pktMatch, UUID ownerId, boolean isPortFilter) {

        if (null == chain) {
            return new RuleResult(Action.ACCEPT, null, pktMatch);
        }

        if (chain.log.isDebugEnabled()) {
            chain.log.debug("Testing {} against Chain:\n{}",
                    pktMatch, chain.asTree(4), fwdInfo);
        }

        // Use ArrayList rather than HashSet because the list will be
        // short enough that O(N) lookup is still cheap, and this
        // avoids per-chain allocation.
        //
        // TODO: We can count how many chains can be reached from a
        // given root chain in the constructor and then use that to
        // determine how big a list to allocate.
        List<UUID> traversedChains = new ArrayList<>();
        RuleResult res = new RuleResult(Action.CONTINUE, null, pktMatch);
        chain.apply(fwdInfo, ownerId, isPortFilter, res, 0, traversedChains);

        // Accept if the chain didn't make an explicit decision.
        if (!res.action.isDecisive())
            res.action = Action.ACCEPT;

        if (traversedChains.size() > 25) {
            // It's unlikely that this will come up a lot, but if it does,
            // consider using a different structure for traversedChains.
            chain.log.warning("Traversed {} chains when applying chain {}.",
                              traversedChains.size(), chain.id, fwdInfo);
        }

        return res;
    }

    public String toString() {
        return "Chain[Name=" + name + ", ID=" + id + "]";
    }

    /**
     * Generates a tree representation of the chain, including its rules and
     * jump targets.
     *
     * @param indent Number of spaces to indent.
     */
    public String asTree(int indent) {
        char[] indentBuf = new char[indent];
        Arrays.fill(indentBuf, ' ');
        StringBuilder bld = new StringBuilder();
        bld.append(indentBuf);
        bld.append(this.toString());

        // Print rules.
        for (Rule rule : rules) {
            bld.append(indentBuf).append("    ");
            bld.append(rule).append('\n');
            if (rule instanceof JumpRule) {
                JumpRule jr = (JumpRule)rule;
                Chain jumpTarget = jumpTargets.get(jr.jumpToChainID).get();
                bld.append(jumpTarget.asTree(indent + 8));
            }
        }

        return bld.toString();
    }

    /**
     * For unit testing.
     */
    public List<Rule> getRules() {
        return rules;
    }
}
