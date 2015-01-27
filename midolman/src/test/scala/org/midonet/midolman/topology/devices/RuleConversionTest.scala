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

package org.midonet.midolman.topology.devices

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Rule.{NatData, JumpData}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules._
import org.midonet.midolman.topology.TopologyBuilder
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.cluster.models.Topology.{Rule => ProtoRule}

@RunWith(classOf[JUnitRunner])
class RuleConversionTest extends MidolmanSpec
                         with TopologyBuilder {

    feature("Conversion of a rule from a proto rule") {
        scenario("Conversion of a proto rule to a jump rule") {
            val protoRule = createJumpRuleBuilder(id = UUID.randomUUID(),
                                                  jumpChainId = UUID.randomUUID(),
                                                  chainId = UUID.randomUUID())
                .build()
            val simRule = Rule.fromProto(protoRule)
            checkSimRule(protoRule, simRule, classOf[JumpRule])
        }

        scenario("Conversion of a proto rule to a forward nat rule") {
            val natTarget = createNatTargetBuilder(IPAddressUtil.toProto("192.168.1.0"),
                                                   IPAddressUtil.toProto("192.168.1.255"),
                                                   20000, 40000)
            val protoRule = createRuleBuilder(id = UUID.randomUUID(),
                                              chainId = UUID.randomUUID())

                .setNatData(NatData.newBuilder
                                .addNatTargets(natTarget)
                                .setIsForward(true)
                                .build())
                .build()
            val simRule = Rule.fromProto(protoRule)
            checkSimRule(protoRule, simRule, classOf[ForwardNatRule])
        }

        scenario("Conversion of a proto rule to a reverse nat rule") {
            val natTarget = createNatTargetBuilder(IPAddressUtil.toProto("192.168.1.0"),
                                                   IPAddressUtil.toProto("192.168.1.255"),
                                                   20000, 40000)
            val protoRule = createRuleBuilder(id = UUID.randomUUID(),
                                              chainId = UUID.randomUUID())

                .setNatData(NatData.newBuilder
                                .addNatTargets(natTarget)
                                .setIsForward(false)
                                .build())
                .build()
            val simRule = Rule.fromProto(protoRule)
            checkSimRule(protoRule, simRule, classOf[ReverseNatRule])
        }

        scenario("Conversion of a proto rule to a trace rule") {
            val protoRule = createRuleBuilder(id = UUID.randomUUID(),
                                              chainId = UUID.randomUUID())
                .setTraceAction(ProtoRule.Action.CONTINUE)
                .build()
            val simRule = Rule.fromProto(protoRule)
            checkSimRule(protoRule, simRule, classOf[TraceRule])
        }

        scenario("Conversion of a proto rule to a literal rule") {
            val protoRule = createRuleBuilder(id = UUID.randomUUID(),
                                              chainId = UUID.randomUUID())
                .setLiteralAction(ProtoRule.Action.CONTINUE)
                .build()
            val simRule = Rule.fromProto(protoRule)
            checkSimRule(protoRule, simRule, classOf[LiteralRule])
         }
    }

    private def checkSimRule(rule: Topology.Rule, simRule: Rule, clazz: Class[_]) = {
        simRule.getClass shouldBe clazz
        simRule.chainId shouldBe rule.getChainId.asJava
    }
}
