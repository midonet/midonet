/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import scala.Option;
import scala.collection.Map;

import com.google.common.annotations.VisibleForTesting;

import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.sdn.flows.FlowTagger;

import static org.midonet.midolman.topology.VirtualTopology.VirtualDevice;

public class Chain implements VirtualDevice {
    public final UUID id;
    private final List<Rule> rules;
    private final Map<UUID, Chain> jumpTargets;
    public final String name;
    public final FlowTagger.FlowTag flowInvTag;

    public Chain(UUID id, List<Rule> rules, Map<UUID, Chain> jumpTargets,
                 String name) {
        this.id = id;
        this.rules = new ArrayList<>(rules);
        this.jumpTargets = jumpTargets;
        this.name = name;
        flowInvTag = FlowTagger.tagForDevice(id);
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

    public FlowTagger.FlowTag deviceTag() {
        return flowInvTag;
    }

    public Chain getJumpTarget(UUID to) {
        Option<Chain> match = jumpTargets.get(to);
        return match.isDefined() ? match.get() : null;
    }

    @VisibleForTesting
    public boolean isJumpTargetsEmpty() {
        return jumpTargets.isEmpty();
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
    private void apply(PacketContext context, UUID ownerId,
                       boolean isPortFilter, RuleResult res,
                       int depth, List<UUID> traversedChains) {

        context.jlog().debug("Processing chain with name {} and ID {}", name, id);
        if (depth > 10) {
            throw new IllegalStateException("Deep recursion when processing " +
                                            "chain " + traversedChains.get(0));
        }

        context.addFlowTag(flowInvTag);
        traversedChains.add(id);

        Iterator<Rule> iter = rules.iterator();
        res.action = Action.CONTINUE;
        while (iter.hasNext() && res.action == Action.CONTINUE) {

            Rule r = iter.next();
            r.process(context, res, ownerId, isPortFilter);

            context.recordTraversedRule(r.id, res);

            if (res.action == Action.JUMP) {
                Chain jumpChain = getJumpTarget(res.jumpToChain);
                if (null == jumpChain) {
                    context.jlog().error("ignoring jump to chain {} : not found.",
                                        res.jumpToChain, context);
                    res.action = Action.CONTINUE;
                } else if (traversedChains.contains(jumpChain.id)) {
                    context.jlog().warn(
                        "cannot jump from chain {} to chain {} -- already visited",
                        this, jumpChain, context);
                    res.action = Action.CONTINUE;
                } else {
                    // Apply the jump chain and return if it produces a
                    // decisive action. If not, on to the next rule.
                    jumpChain.apply(context, ownerId, isPortFilter,
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
     * @param context
     *            The packet's PacketContext.
     * @param ownerId
     *            UUID of the element using chainId.
     * @param isPortFilter
     *            whether the chain is being processed in a port filter context
     */
    public static RuleResult apply(
            Chain chain, PacketContext context,
            UUID ownerId, boolean isPortFilter) {

        if (null == chain) {
            return new RuleResult(Action.ACCEPT, null);
        }

        if (context.jlog().isDebugEnabled()) {
            context.jlog().debug("Testing against Chain:\n{}", chain.asList(4, false));
        }

        // Use ArrayList rather than HashSet because the list will be
        // short enough that O(N) lookup is still cheap, and this
        // avoids per-chain allocation.
        //
        // TODO: We can count how many chains can be reached from a
        // given root chain in the constructor and then use that to
        // determine how big a list to allocate.
        List<UUID> traversedChains = new ArrayList<>();
        RuleResult res = new RuleResult(Action.CONTINUE, null);
        chain.apply(context, ownerId, isPortFilter, res, 0, traversedChains);

        // Accept if the chain didn't make an explicit decision.
        if (!res.action.isDecisive())
            res.action = Action.ACCEPT;

        if (traversedChains.size() > 25) {
            // It's unlikely that this will come up a lot, but if it does,
            // consider using a different structure for traversedChains.
            context.jlog().warn("Traversed {} chains when applying chain {}.",
                               traversedChains.size(), chain.id, context);
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
        return asList(indent, true);
    }

    public String asList(int indent, boolean recursive) {
        char[] indentBuf = new char[indent];
        Arrays.fill(indentBuf, ' ');
        StringBuilder bld = new StringBuilder();
        bld.append(indentBuf);
        bld.append(this.toString());

        // Print rules.
        for (Rule rule : rules) {
            bld.append(indentBuf).append("    ");
            bld.append(rule).append('\n');
            if (recursive && rule instanceof JumpRule) {
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
