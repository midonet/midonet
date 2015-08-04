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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.services.c3po.C3POStorageManager.Operation
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Topology.Condition
import org.midonet.cluster.models.Topology.Condition.FragmentPolicy
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.Action._
import org.midonet.cluster.models.Topology.Rule.{JumpRuleData, NatRuleData, NatTarget}
import org.midonet.cluster.util.UUIDUtil

/**
 * Contains rule-related operations shared by multiple translators.
 */
trait RuleManager {
    protected def newRule(chainId: UUID): Rule.Builder =
        Rule.newBuilder().setChainId(chainId).setId(UUIDUtil.randomUuidProto)

    protected def returnFlowRule(chainId: UUID): Rule =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(ACCEPT)
            .setCondition(Condition.newBuilder.setMatchReturnFlow(true))
            .build()

    protected def dropRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(DROP)

    protected def dropRuleCondition(): Condition.Builder =
        Condition.newBuilder.setFragmentPolicy(FragmentPolicy.ANY)

    protected def jumpRule(fromChain: UUID, toChain: UUID): Rule =
        newRule(fromChain)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(JUMP)
            .setJumpRuleData(JumpRuleData.newBuilder
                                 .setJumpTo(toChain)
                                 .build())
            .build()

    protected def returnRule(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(RETURN)

    protected def returnCondition(): Condition.Builder =
        Condition.newBuilder.setFragmentPolicy(FragmentPolicy.ANY)

    protected def natRuleData(addr: IPAddress, dnat: Boolean,
                              dynamic: Boolean = true): NatRuleData = {
        if (dynamic)
            natRuleData(addr, dnat, 1, 0xffff)
        else
            natRuleData(addr, dnat, 0, 0)
    }

    protected def natRuleData(addr: IPAddress, dnat: Boolean, portStart: Int,
                              portEnd: Int): NatRuleData = {
        val target = NatTarget.newBuilder
            .setNwStart(addr).setNwEnd(addr)
            .setTpStart(portStart).setTpEnd(portEnd).build()
        NatRuleData.newBuilder
            .setDnat(dnat)
            .addNatTargets(target).build()
    }

    protected def revNatRuleData(dnat: Boolean): NatRuleData = {
        NatRuleData.newBuilder.setDnat(dnat).setReverse(true).build()
    }

    protected def toRuleIdList(ops: Seq[MidoOp[Rule]]) = ops.map {
        case Create(r: Rule) => r.getId
        case Update(r: Rule, _) => r.getId
        case Delete(_, id) => id
    }

}
