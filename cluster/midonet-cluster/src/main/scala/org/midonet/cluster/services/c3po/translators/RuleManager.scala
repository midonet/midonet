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

import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.Commons.{Condition, IPAddress, UUID}
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.Action._
import org.midonet.cluster.models.Topology.Rule.{JumpRuleData, NatRuleData, NatTarget}
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.cluster.util.UUIDUtil

/**
 * Contains rule-related operations shared by multiple translators.
 */
trait RuleManager {
    protected def newRule(chainId: UUID): Rule.Builder =
        Rule.newBuilder().setChainId(chainId).setId(UUIDUtil.randomUuidProto)

    protected def returnFlowRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(ACCEPT)
            .setCondition(
                Condition.newBuilder.setMatchReturnFlow(true).build())

    protected def returnFlowRule(chainId: UUID): Rule =
        returnFlowRuleBuilder(chainId).build()

    protected def returnFlowRule(id: UUID, chainId: UUID): Rule =
        returnFlowRuleBuilder(chainId).setId(id).build()

    protected def forwardFlowRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(CONTINUE)
            .setCondition(
                Condition.newBuilder().setMatchForwardFlow(true).build())

    protected def forwardFlowRule(id: UUID, chainId: UUID): Rule =
        forwardFlowRuleBuilder(chainId).setId(id).build()

    protected def dropRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(DROP)

    protected def dropRule(id: UUID, chainId: UUID): Rule =
        dropRuleBuilder(chainId).setId(id).build()

    protected def jumpRuleBuilder(fromChain: UUID,
                                  toChain: UUID): Rule.Builder =
        newRule(fromChain)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(JUMP)
            .setJumpRuleData(JumpRuleData.newBuilder
                                 .setJumpTo(toChain)
                                 .build())

    protected def jumpRule(fromChain: UUID, toChain: UUID): Rule =
        jumpRuleBuilder(fromChain, toChain).build()

    protected def jumpRule(id: UUID, fromChain: UUID, toChain: UUID): Rule =
        jumpRuleBuilder(fromChain, toChain).setId(id).build()

    protected def returnRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(RETURN)

    protected def returnRule(chainId: UUID): Rule =
        returnRuleBuilder(chainId).build()

    protected def returnRule(id: UUID, chainId: UUID): Rule =
        returnRuleBuilder(chainId).setId(id).build()

    protected def anyFragCondition(): Condition.Builder =
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

    /**
     * Gets the operations necessary to replace oldRules with newRules in the
     * topology store. Will generate operations to:
     *
     * 1. Delete rules which are in oldRules but not in newRules.
     * 2. Update rules which are in both lists but have changed in some way.
     * 3. Create rules which are in newRules but not in oldRules.
     *
     * A rule in one list is considered also to be in the other list if the
     * other list contains a rule with the same ID.
     *
     * No operations are generated for rules which are identical in both lists.
     */
    protected def getRuleChanges(oldRules: List[Rule], newRules: List[Rule])
    : Tuple3[List[Rule], List[Rule], List[UUID]] = {
        val oldRuleIds = oldRules.map(_.getId)
        val newRuleIds = newRules.map(_.getId)

        val removedIds = oldRuleIds.diff(newRuleIds)
        val addedIds = newRuleIds.diff(oldRuleIds)
        val (addedRules, keptRules) =
            newRules.partition(rule => addedIds.contains(rule.getId))

        // No need to update rules that aren't changing.
        val updatedRules = keptRules.diff(oldRules)

        (addedRules, updatedRules, removedIds)
    }
}
