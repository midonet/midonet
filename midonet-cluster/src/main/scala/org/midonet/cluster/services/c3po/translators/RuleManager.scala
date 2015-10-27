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
    protected def newRule(chainId: UUID): Rule.Builder = {
        val bldr = Rule.newBuilder()
        if (chainId != null) bldr.setChainId(chainId)
        bldr.setId(UUIDUtil.randomUuidProto)
    }

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

    protected def jumpRuleBuilder(fromChain: UUID,
                                  toChain: UUID): Rule.Builder =
        newRule(fromChain)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(JUMP)
            .setJumpRuleData(JumpRuleData.newBuilder
                                 .setJumpChainId(toChain)
                                 .build())

    protected def jumpRule(fromChain: UUID, toChain: UUID): Rule =
        jumpRuleBuilder(fromChain, toChain).build()

    protected def jumpRuleWithId(id: UUID, fromChain: UUID,
                                 toChain: UUID): Rule =
        jumpRuleBuilder(fromChain, toChain).setId(id).build()

    protected def forwardFlowRuleBuilder(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(CONTINUE)
            .setCondition(
                Condition.newBuilder().setMatchForwardFlow(true).build())

    protected def forwardFlowRule(chainId: UUID): Rule =
        forwardFlowRuleBuilder(chainId).build()

    protected def returnRule(chainId: UUID): Rule.Builder =
        newRule(chainId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(RETURN)

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
     * Gets 2 lists of old and new rules, and returns 3 lists of added rules,
     * updated rules, and deleted rule IDs.  The output lists are determined as
     * follows:
     *
     * 1. Delete rules which are in oldRules but not in newRules.
     * 2. Update rules which are in both lists but have changed in some way.
     * 3. Create rules which are in newRules but not in oldRules.
     */
    protected def getRuleChanges(oldRules: List[Rule], newRules: List[Rule])
    : (List[Rule], List[Rule], List[UUID]) = {
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
