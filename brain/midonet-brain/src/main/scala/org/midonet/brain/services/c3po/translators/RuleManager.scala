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

package org.midonet.brain.services.c3po.translators

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.midonet.{Delete, Update, Create}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.Action._
import org.midonet.cluster.models.Topology.Rule.Condition.FragmentPolicy
import org.midonet.cluster.models.Topology.Rule.{JumpData, Condition, NatData}
import org.midonet.cluster.util.UUIDUtil

/**
 * Contains rule-related operations shared by multiple translators.
 */
trait RuleManager {
    protected def reverseFlowRule(chainId: UUID): Rule =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
        .setChainId(chainId)
        .setNatData(NatData.newBuilder
                        .setAction(ACCEPT)
                        .setIsForward(false)
                        .build())
        .build()

    protected def dropRuleBuilder(chainId: UUID): Rule.Builder =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
        .setChainId(chainId)
        .setLiteralAction(DROP)
            .setCondition(Condition.newBuilder
                              .setFragmentPolicy(FragmentPolicy.ANY)
                              .build())

    protected def jumpRule(fromChain: UUID, toChain: UUID): Rule =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
            .setJumpData(JumpData.newBuilder
                             .setJumpTo(toChain)
                             .build())
        .setChainId(fromChain)
        .build()

    protected def toRuleIdList(ops: Seq[Operation[Rule]]) = ops.map {
        case Create(r: Rule) => r.getId
        case Update(r: Rule) => r.getId
        case Delete(_, id) => id
    }

}
