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

import java.util
import java.util.{UUID, List => JList, Map => JMap}

import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.rules.{JumpRule, Rule, RuleResult}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger

object Chain {
    private val traversedChainsThreadLocal =
        new ThreadLocal[util.ArrayList[UUID]] {
            override def initialValue = new util.ArrayList[UUID]()
        }

    val Accept = new RuleResult(Action.ACCEPT)
    val Drop = new RuleResult(Action.DROP)
    val Continue = new RuleResult(Action.CONTINUE)

    val NoMetadata = Array[Byte]()
}

case class Chain(id: UUID,
                 rules: JList[Rule],
                 jumpTargets: JMap[UUID, Chain],
                 name: String,
                 metadata: Array[Byte] = Chain.NoMetadata,
                 ruleLoggers: Seq[RuleLogger] = Seq())
    extends VirtualDevice with SimDevice {
    import Chain._

    override val deviceTag: FlowTagger.FlowTag = FlowTagger.tagForChain(id)
    override def adminStateUp = true

    def getJumpTarget(to: UUID): Chain = jumpTargets.get(to)

    @VisibleForTesting def isJumpTargetsEmpty: Boolean = jumpTargets.isEmpty

    def process(context: PacketContext): RuleResult = {
        val traversedChains = Chain.traversedChainsThreadLocal.get()
        traversedChains.clear()
        val res = apply(context, traversedChains)
        if (traversedChains.size > 25) {
            context.log.warn(s"Traversed ${traversedChains.size} chains " +
                             s"when applying chain $id")
        }
        if (res.isDecisive)
            res
        else
            Accept
    }

    private def apply(context: PacketContext,
                      traversedChains: util.ArrayList[UUID]): RuleResult = {
        context.log.debug(s"Testing against $toString")

        context.addFlowTag(deviceTag)
        traversedChains.add(id)
        var i = 0
        var res = Continue
        while ((i < rules.size()) && (res.action eq Action.CONTINUE)) {
            val rule = rules.get(i)
            i += 1
            res = rule.process(context)

            res.action match {
                case Action.ACCEPT | Action.RETURN =>
                    var i = 0
                    while (i < ruleLoggers.size) {
                        ruleLoggers(i).logAccept(context, this, rule)
                        i += 1
                    }
                case Action.DROP | Action.REJECT =>
                    var i = 0
                    while (i < ruleLoggers.size) {
                        ruleLoggers(i).logDrop(context, this, rule)
                        i += 1
                    }
                case _ =>
            }

            if (rule.id == null) {
                context.log.warn(s"Rule $rule missing identifier")
            } else {
                context.recordTraversedRule(rule.id, res)
            }

            if (res.action eq Action.JUMP)
                res = jump(context, res.jumpToChain, traversedChains)
        }
        assert(res.action ne Action.JUMP)
        res
    }

    private[this] def jump(context: PacketContext,
                           jumpChainId: UUID,
                           traversedChains:util.ArrayList[UUID]): RuleResult = {
        val jumpChain: Chain = getJumpTarget(jumpChainId)
        if (jumpChain == null) {
            context.log.error(s"Ignoring non-existing jump chain $jumpChainId")
            Continue
        } else if (traversedChains.contains(jumpChainId)) {
            context.log.warn(s"Jump chain $jumpChainId already visited")
            Continue
        } else {
            val res = jumpChain.apply(context, traversedChains)
            if (res.action == Action.RETURN)
                Continue
            else
                res
        }
    }

    override def toString: String = s"Chain [name=$name id=$id rules=$rules]"

}
