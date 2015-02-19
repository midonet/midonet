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

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.{Type, Action}
import org.midonet.cluster.util.{RangeUtil, IPSubnetUtil, IPAddressUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.{Rule => SimRule, _}
import org.midonet.midolman.topology.TopologyBuilder
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class RuleConversionTest extends FeatureSpec with Matchers
                                             with TopologyBuilder {

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
            assertEquals(rule, simRule)
        }

        scenario("Test conversion for a trace rule") {
            val rule = createTraceRule(id = UUID.randomUUID(),
                                       chainId = Some(UUID.randomUUID()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            assertEquals(rule, simRule)
        }

        scenario("Test conversion for a jump rule") {
            val rule = createJumpRule(id = UUID.randomUUID(),
                                      chainId = Some(UUID.randomUUID()),
                                      jumpChainId = Some(UUID.randomUUID()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            assertEquals(rule, simRule)
        }

        scenario("Test conversion for a forward NAT rule") {
            val rule = createNatRule(id = UUID.randomUUID,
                                     chainId = Some(UUID.randomUUID),
                                     matchFwdFlow = Some(true),
                                     dnat = Some(false),
                                     Set(createNatTarget()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            assertEquals(rule, simRule)
            simRule.asInstanceOf[ForwardNatRule].isFloatingIp shouldBe false
        }

        scenario("Test conversion for a floating IP") {
            val natTarget = createNatTarget(IPAddressUtil.toProto("192.168.0.1"),
                                            IPAddressUtil.toProto("192.168.0.1"),
                                            portStart = 0,
                                            portEnd = 0)
            val rule = createNatRule(id = UUID.randomUUID,
                                     chainId = Some(UUID.randomUUID),
                                     matchFwdFlow = Some(true),
                                     targets = Set(natTarget))

            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            assertEquals(rule, simRule)
            simRule.asInstanceOf[ForwardNatRule].isFloatingIp shouldBe true
            simRule.asInstanceOf[ForwardNatRule]
                .getFloatingIpAddr shouldBe "192.168.0.1"
        }

        scenario("Test conversion for a reverse NAT rule") {
            val rule = createNatRule(id = UUID.randomUUID(),
                                     chainId = Some(UUID.randomUUID()),
                                     matchFwdFlow = Some(false),
                                     dnat = Some(true),
                                     Set(createNatTarget()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            assertEquals(rule, simRule)
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

    private def assertEquals(rule: Rule, simRule: SimRule): Unit = {
        rule.getAction.name shouldBe simRule.action.name
        rule.getChainId shouldBe simRule.chainId.asProto

        rule.getType match {
            case Type.LITERAL_RULE =>
                simRule.getClass shouldBe classOf[LiteralRule]
            case Type.TRACE_RULE =>
                simRule.getClass shouldBe classOf[TraceRule]
            case Type.JUMP_RULE =>
                simRule.getClass shouldBe classOf[JumpRule]
                simRule.asInstanceOf[JumpRule].jumpToChainID shouldBe
                    rule.getJumpRuleData.getJumpTo.asJava
            case Type.NAT_RULE =>
                if (rule.getMatchForwardFlow) {
                    simRule.getClass shouldBe classOf[ForwardNatRule]
                    simRule.asInstanceOf[ForwardNatRule]
                        .getTargetsArray should not be null
                    assertNatTargets(rule.getNatRuleData.getNatTargetsList
                                         .asScala.toList,
                                     simRule.asInstanceOf[ForwardNatRule]
                                         .getNatTargets.asScala.toSet)
                } else if (rule.getMatchReturnFlow)
                    simRule.getClass shouldBe classOf[ReverseNatRule]
            case _ => throw new IllegalArgumentException("Unrecognized rule " +
                                                         s"type: ${rule.getType}")

        }
        assertCondition(rule, simRule.getCondition)
    }

    private def assertNatTargets(protoTargets: List[Rule.NatTarget],
                                 targets: Set[NatTarget]): Unit = {
        targets should contain theSameElementsAs protoTargets.map({target =>
                new NatTarget(IPAddressUtil.toIPv4Addr(target.getNwStart),
                              IPAddressUtil.toIPv4Addr(target.getNwEnd),
                              target.getTpStart, target.getTpEnd)
        })
    }

    private def assertCondition(rule: Rule, cond: Condition): Unit = {
        rule.getConjunctionInv shouldBe cond.conjunctionInv
        rule.getMatchForwardFlow shouldBe cond.matchForwardFlow
        rule.getMatchReturnFlow shouldBe cond.matchReturnFlow

        rule.getInPortIdsCount shouldBe cond.inPortIds.size
        rule.getInPortIdsList.asScala.map(_.asJava) should
            contain theSameElementsAs cond.inPortIds.asScala
        rule.getInPortInv shouldBe cond.inPortInv

        rule.getOutPortIdsCount shouldBe cond.outPortIds.size
        rule.getOutPortIdsList.asScala.map(_.asJava) should
            contain theSameElementsAs cond.outPortIds.asScala
        rule.getOutPortInv shouldBe cond.outPortInv

        rule.getPortGroupId shouldBe cond.portGroup.asProto
        rule.getInvPortGroup shouldBe cond.invPortGroup
        rule.getIpAddrGroupIdSrc shouldBe cond.ipAddrGroupIdSrc.asProto
        rule.getInvIpAddrGroupIdSrc shouldBe cond.invIpAddrGroupIdSrc
        rule.getIpAddrGroupIdDst shouldBe cond.ipAddrGroupIdDst.asProto
        rule.getInvIpAddrGroupIdDst shouldBe cond.invIpAddrGroupIdDst
        rule.getDlType shouldBe cond.etherType
        rule.getInvDlType shouldBe cond.invDlType
        rule.getDlSrc shouldBe cond.ethSrc.toString
        rule.getDlSrcMask shouldBe cond.ethSrcMask
        rule.getInvDlSrc shouldBe cond.invDlSrc
        rule.getDlDst shouldBe cond.ethDst.toString
        rule.getDlDstMask shouldBe cond.dlDstMask
        rule.getInvDlDst shouldBe cond.invDlDst
        rule.getNwTos shouldBe cond.nwTos
        rule.getNwTosInv shouldBe cond.nwTosInv
        rule.getNwProto shouldBe cond.nwProto
        rule.getNwProtoInv shouldBe cond.nwProtoInv
        rule.getNwSrcIp shouldBe IPSubnetUtil.toProto(cond.nwSrcIp)
        rule.getNwDstIp shouldBe IPSubnetUtil.toProto(cond.nwDstIp)
        rule.getTpSrc shouldBe RangeUtil.toProto(cond.tpSrc)
        rule.getTpDst shouldBe RangeUtil.toProto(cond.tpDst)
        rule.getNwSrcInv shouldBe cond.nwSrcInv
        rule.getNwDstInv shouldBe cond.nwDstInv
        rule.getTpSrcInv shouldBe cond.tpSrcInv
        rule.getTpDstInv shouldBe cond.tpDstInv
        rule.getTraversedDevice shouldBe cond.traversedDevice.asProto
        rule.getTraversedDeviceInv shouldBe cond.traversedDeviceInv
        rule.getFragmentPolicy.name shouldBe cond.fragmentPolicy.name
    }
}
