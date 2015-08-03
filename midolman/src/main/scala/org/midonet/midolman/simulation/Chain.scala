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

import java.util.ArrayList
import java.util.Arrays
import java.util.UUID
import java.util.{Map => JMap, List => JList}

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.rules.JumpRule
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.sdn.flows.FlowTagger
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice

object Chain {
    private val traversedChainsTL = new ThreadLocal[ArrayList[UUID]] {
        override def initialValue = new ArrayList[UUID]()
    }

    /**
     * @param chain            The chain where processing starts.
     * @param context          The packet's PacketContext.
     * @param ownerId          UUID of the element using chainId.
     */
    def apply(chain: Chain, context: PacketContext, ownerId: UUID): RuleResult = {
        if (chain == null)
            return Simulator.RuleResults(Action.ACCEPT, null)

        context.log.debug(s"Testing against Chain: ${chain.asList(4, false)}")

        val traversedChains = traversedChainsTL.get()
        traversedChains.clear()
        val res: RuleResult = Simulator.RuleResults(Action.CONTINUE, null)
        chain.apply(context, ownerId, res, traversedChains)
        if (!res.action.isDecisive)
            res.action = Action.ACCEPT
        if (traversedChains.size > 25) {
            context.log.warn(s"Traversed ${traversedChains.size} chains " +
                s"when applying chain ${chain.id}.")
        }
        res
    }
}

case class Chain(id: UUID,
                 rules: JList[Rule],
                 jumpTargets: JMap[UUID, Chain],
                 name: String) extends VirtualDevice {

    override val deviceTag: FlowTagger.FlowTag = FlowTagger.tagForChain(id)

    def getJumpTarget(to: UUID): Chain = jumpTargets.get(to)

    @VisibleForTesting def isJumpTargetsEmpty: Boolean = jumpTargets.isEmpty

    /*
     * Recursive helper function for public static apply(). The first
     * three parameters are the same as in that method.
     *
     * @param res              Results of rule processing (out only), plus packet
     *                         match (in/out).
     * @param traversedChains  Keeps track of chains that have been visited to
     *                         prevent infinite recursion in the event of a
     *                         cycle.
     */
    private def apply(context: PacketContext, ownerId: UUID, res: RuleResult,
                      traversedChains: ArrayList[UUID]): Unit = {
        context.addFlowTag(deviceTag)
        traversedChains.add(id)
        res.action = Action.CONTINUE
        var i = 0
        while ((i < rules.size()) && !res.action.isDecisive) {
            val rule = rules.get(i)
            i += 1
            rule.process(context, res, ownerId)
            context.recordTraversedRule(rule.id, res)
            if (res.action eq Action.JUMP)
                jump(context, ownerId, res, traversedChains)
        }
        assert(res.action ne Action.JUMP)
    }

    private[this] def jump(context: PacketContext, ownerId: UUID, res: RuleResult,
                           traversedChains: ArrayList[UUID]): Unit = {
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
            jumpChain.apply(context, ownerId, res, traversedChains)
            if (res.action == Action.RETURN)
                res.action = Action.CONTINUE
        }
    }

    override def toString: String = "Chain[name=" + name + ", id=" + id + "]"

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
        var i = 0
        while (i < rules.size()) {
            val rule = rules.get(i)
            i += 1
            bld.append(indentBuf).append("    ")
            bld.append(rule).append('\n')
            if (recursive && rule.isInstanceOf[JumpRule]) {
                val jr: JumpRule = rule.asInstanceOf[JumpRule]
                val jumpTarget: Chain = jumpTargets.get(jr.jumpToChainID)
                bld.append(jumpTarget.asTree(indent + 8))
            }
        }
        bld.toString()
    }
}
