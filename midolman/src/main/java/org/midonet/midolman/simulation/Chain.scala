/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.simulation

import java.util.Arrays
import java.util.UUID
import scala.collection.Map
import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.rules.JumpRule
import org.midonet.midolman.rules.MirrorRule
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.Coordinator.CPAction
import org.midonet.sdn.flows.FlowTagger
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice

object Chain {
    /**
     * @param chain            The chain where processing starts.
     * @param context          The packet's PacketContext.
     * @param ownerId          UUID of the element using chainId.
     * @param isPortFilter     whether the chain is being processed in a port
     *                         filter context
     * @param checkpointAction The function to be used in the rule applications.
     */
    def apply(chain: Chain, context: PacketContext, ownerId: UUID,
              isPortFilter: Boolean,
              checkpointAction: CPAction = Coordinator.NO_CHECKPOINT): RuleResult = {
        if (chain == null) {
            return new RuleResult(Action.ACCEPT, null)
        }
        context.log.debug(s"Testing against Chain: ${chain.asList(4, false)}")
        val traversedChains =  mutable.ListBuffer.empty[UUID]
        val res: RuleResult = new RuleResult(Action.CONTINUE, null)
        chain.apply(context, ownerId, isPortFilter, res, 0,
            traversedChains, checkpointAction)
        if (!res.action.isDecisive) {
            res.action = Action.ACCEPT
        }
        if (traversedChains.size > 25) {
            context.log.warn(s"Traversed ${traversedChains.size} chains " +
                s"when applying chain ${chain.id}.")
        }
        res
    }
}

class Chain(val id: UUID,
            var rules: Seq[Rule],
            var jumpTargets: Map[UUID, Chain],
            val name: String) extends VirtualDevice {

    private val flowInvTag: FlowTagger.FlowTag = FlowTagger.tagForChain(id)

    override def hashCode: Int = id.hashCode

    override def equals(that: Any): Boolean = {
        if (that == null || (this.getClass ne that.getClass)) {
            return false
        }

        that match {
            case that: Chain =>
                if (this == that) {
                    return true
                }
                this.isInstanceOf[Chain] &&
                    this.id == that.id &&
                    this.rules == that.rules &&
                    this.jumpTargets == that.rules &&
                    this.name == that.name
            case _ => false
        }
    }

    def deviceTag: FlowTagger.FlowTag = flowInvTag

    def getJumpTarget(to: UUID): Chain = {
        val matchedChain: Option[Chain] = jumpTargets.get(to)
        matchedChain.orNull
    }

    @VisibleForTesting def isJumpTargetsEmpty: Boolean = jumpTargets.isEmpty

    /*
     * Recursive helper function for public static apply(). The first
     * three parameters are the same as in that method.
     *
     * @param res              Results of rule processing (out only), plus packet
     *                         match (in/out).
     * @param depth            Depth of jump recursion. Guards against excessive
     *                         recursion.
     * @param traversedChains  Keeps track of chains that have been visited to
     *                         prevent infinite recursion in the event of a
     *                         cycle.
     * @param checkpointAction
     */
    private def apply(context: PacketContext, ownerId: UUID,
                      isPortFilter: Boolean, res: RuleResult,
                      depth: Int,
                      traversedChains: mutable.ListBuffer[UUID],
                      checkpointAction: CPAction = Coordinator.NO_CHECKPOINT): Unit = {
        context.log.debug(s"Processing chain with name $name and ID $id")
        if (depth > 10) {
            throw new IllegalStateException("Deep recursion when processing " +
                "chain " + traversedChains.head)
        }
        context.addFlowTag(flowInvTag)
        traversedChains.prepend(id)
        res.action = Action.CONTINUE
        for (r <- rules if res.action == Action.CONTINUE) {
            r.process(context, res, ownerId, isPortFilter)
            context.recordTraversedRule(r.id, res)
            if (res.action eq Action.JUMP) {
                val jumpChain: Chain = getJumpTarget(res.jumpToChain)
                if (jumpChain == null) {
                    context.log.error("ignoring jump to chain " +
                        s"${res.jumpToChain} : not found.")
                    res.action = Action.CONTINUE
                } else if (traversedChains.contains(jumpChain.id)) {
                    context.log.warn(s"cannot jump from chain $this to chain" +
                        s" $jumpChain -- already visited")

                    res.action = Action.CONTINUE
                } else {
                    jumpChain.apply(context, ownerId, isPortFilter, res,
                        depth + 1, traversedChains, checkpointAction)
                    if (res.action == Action.RETURN) {
                        res.action = Action.CONTINUE
                    }
                }
            } else if (res.action eq Action.MIRROR) {
                r match {
                    case mirrorRule: MirrorRule
                            if mirrorRule.getDstPortId != null =>
                        mirrorRule.getDstPortId
                    case _ =>
                        context.log.error(
                            "ignoring mirror rule without the port ID")
                }
                res.action = Action.CONTINUE
                checkpointAction(context)
            }
        }
        assert(res.action ne Action.JUMP)
    }

    override def toString: String = "Chain[Name=" + name + ", ID=" + id + "]"

    /**
     * Generates a tree representation of the chain, including its rules and
     * jump targets.
     *
     * @param indent Number of spaces to indent.
     */
    def asTree(indent: Int): String = asList(indent, true)

    def asList(indent: Int, recursive: Boolean): String = {
        val indentBuf: Array[Char] = new Array[Char](indent)
        Arrays.fill(indentBuf, ' ')
        val bld: StringBuilder = new StringBuilder
        bld.append(indentBuf)
        bld.append(this.toString)
        for (rule <- rules) {
            bld.append(indentBuf).append("    ")
            bld.append(rule).append('\n')
            if (recursive && rule.isInstanceOf[JumpRule]) {
                val jr: JumpRule = rule.asInstanceOf[JumpRule]
                val jumpTarget: Chain = jumpTargets.get(jr.jumpToChainID).get
                bld.append(jumpTarget.asTree(indent + 8))
            }
        }
        bld.toString()
    }
}
