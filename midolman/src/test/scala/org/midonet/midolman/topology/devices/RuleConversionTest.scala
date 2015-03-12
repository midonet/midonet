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
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.midolman.rules.{Rule => SimRule, _}
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}

@RunWith(classOf[JUnitRunner])
class RuleConversionTest extends FeatureSpec with Matchers
                                             with TopologyBuilder
                                             with TopologyMatchers {

    feature("Conversion for rule") {
        scenario("Conversion of a pojo rule to a proto buff is not supported") {
            val simRule = new LiteralRule(new Condition, RuleResult.Action.ACCEPT,
                                          UUID.randomUUID() /* chainId */, 0)

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.toProto(simRule, classOf[Rule])
            }
        }

        scenario("Test conversion for a literal rule") {
            val rule = createLiteralRule(id = UUID.randomUUID(),
                                         chainId = Some(UUID.randomUUID()),
                                         Some(Action.ACCEPT))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a trace rule") {
            val rule = createTraceRule(id = UUID.randomUUID(),
                                       chainId = Some(UUID.randomUUID()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a jump rule") {
            val rule = createJumpRule(id = UUID.randomUUID(),
                                      chainId = Some(UUID.randomUUID()),
                                      jumpChainId = Some(UUID.randomUUID()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a forward NAT rule") {
            val rule = createNatRule(id = UUID.randomUUID,
                                     chainId = Some(UUID.randomUUID),
                                     matchFwdFlow = Some(true),
                                     dnat = Some(false),
                                     Set(createNatTarget()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a floating IP") {
            val natTarget = createNatTarget(toProto("192.168.0.1"),
                                            toProto("192.168.0.1"),
                                            portStart = 0,
                                            portEnd = 0)
            val rule = createNatRule(id = UUID.randomUUID,
                                     chainId = Some(UUID.randomUUID),
                                     matchFwdFlow = Some(true),
                                     targets = Set(natTarget))

            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule.asInstanceOf[ForwardNatRule]
                .getFloatingIpAddr shouldBe "192.168.0.1"
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a reverse NAT rule") {
            val rule = createNatRule(id = UUID.randomUUID(),
                                     chainId = Some(UUID.randomUUID()),
                                     matchFwdFlow = Some(false),
                                     dnat = Some(true),
                                     Set(createNatTarget()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }
    }

    feature("Protocol buffer validation") {
        scenario("Test protobuf validation with a rule without an action") {
            val rule = createLiteralRule(id = UUID.randomUUID(),
                                         chainId = Some(UUID.randomUUID()))

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("Test protobuf validation with a jump rule without a JUMP action") {
            val rule = createJumpRule(id = UUID.randomUUID(),
                                      chainId = Some(UUID.randomUUID()),
                                      jumpChainId = Some(UUID.randomUUID()))
                      .toBuilder
                      .setAction(Rule.Action.ACCEPT)
                      .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A nat rule that's neither a fwd nor a reverse rule") {
            val rule = createNatRule(id = UUID.randomUUID(),
                                     chainId = Some(UUID.randomUUID()),
                                     dnat = Some(false),
                                     targets = Set(createNatTarget()))

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A reverse nat rule without dnat set") {
            val rule = createNatRule(id = UUID.randomUUID(),
                                     chainId = Some(UUID.randomUUID()),
                                     matchFwdFlow = Some(false),
                                     targets = Set(createNatTarget()))

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A fwd nat rule with no targets") {
            val rule = createNatRule(id = UUID.randomUUID(),
                                     chainId = Some(UUID.randomUUID()),
                                     matchFwdFlow = Some(true),
                                     dnat = Some(true),
                                     Set.empty)

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A trace rule with an action different than CONTINUE") {
            val rule = createTraceRule(id = UUID.randomUUID(),
                                       chainId = Some(UUID.randomUUID()))
                .toBuilder
                .setAction(Action.JUMP)
                .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }
    }
}
