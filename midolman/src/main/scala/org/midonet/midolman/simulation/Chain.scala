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

    val ACCEPT = new RuleResult(Action.ACCEPT)
    val DROP = new RuleResult(Action.DROP)
    val CONTINUE = new RuleResult(Action.CONTINUE)
}

case class Chain(id: UUID,
                 rules: JList[Rule],
                 jumpTargets: JMap[UUID, Chain],
                 name: String) extends VirtualDevice with SimDevice {
    import Chain._

    override val deviceTag: FlowTagger.FlowTag = FlowTagger.tagForChain(id)
    override def adminStateUp = true

    def getJumpTarget(to: UUID): Chain = jumpTargets.get(to)

    @VisibleForTesting def isJumpTargetsEmpty: Boolean = jumpTargets.isEmpty

    def process(context: PacketContext): RuleResult = {
        context.log.debug(s"Testing against ${asList(0, recursive = false)}")

        val traversedChains = Chain.traversedChainsTL.get()
        traversedChains.clear()
        val res = apply(context, traversedChains)
        if (traversedChains.size > 25) {
            context.log.warn(s"Traversed ${traversedChains.size} chains " +
                             s"when applying chain $id.")
        }
        if (res.isDecisive)
            res
        else
            ACCEPT
    }

    private def apply(
            context: PacketContext,
            traversedChains: ArrayList[UUID]): RuleResult = {
        context.addFlowTag(deviceTag)
        traversedChains.add(id)
        var i = 0
        var res = CONTINUE
        while ((i < rules.size()) && (res.action eq Action.CONTINUE)) {
            val rule = rules.get(i)
            i += 1
            res = rule.process(context)
            context.recordTraversedRule(rule.id, res)
            if (res.action eq Action.JUMP)
                res = jump(context, res.jumpToChain, traversedChains)
        }
        assert(res.action ne Action.JUMP)
        res
    }

    private[this] def jump(
            context: PacketContext,
            jumpToChain: UUID,
            traversedChains: ArrayList[UUID]): RuleResult = {
        val jumpChain: Chain = getJumpTarget(jumpToChain)
        if (jumpChain == null) {
            context.log.error(s"ignoring jump to chain $jumpToChain : not found.")
            Chain.CONTINUE
        } else if (traversedChains.contains(jumpChain.id)) {
            context.log.warn(s"cannot jump from chain $this to chain" +
                s" $jumpChain -- already visited")
            Chain.CONTINUE
        } else {
            val res = jumpChain.apply(context, traversedChains)
            if (res.action == Action.RETURN)
                Chain.CONTINUE
            else
                res
        }
    }

    override def toString: String = "Chain[name=" + name + ", id=" + id + "]"

    /**
     * Generates a tree representation of the chain, including its rules and
     * jump targets.
     *
     * @param indent Number of spaces to indent.
     */
    def asTree(indent: Int): String = asList(indent, recursive = true)

    def asList(indent: Int, recursive: Boolean): String = {
        val indentBuf = new Array[Char](indent)
        Arrays.fill(indentBuf, ' ')
        val indentStr = new String(indentBuf)
        val bld = new StringBuilder
        bld.append(indentStr)
        bld.append(this.toString)
        var i = 0
        while (i < rules.size()) {
            val rule = rules.get(i)
            i += 1
            bld.append(indentStr).append("    ")
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
