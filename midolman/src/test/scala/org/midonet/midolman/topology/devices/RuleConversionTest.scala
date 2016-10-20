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
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.{Condition => TopologyCondition}
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, RangeUtil}
import org.midonet.midolman.rules.{Rule => SimRule, _}
import org.midonet.packets.{IPSubnet, IPv4Subnet, MAC}
import org.midonet.util.Range

@RunWith(classOf[JUnitRunner])
class RuleConversionTest extends FeatureSpec with Matchers
                                             with TopologyBuilder
                                             with TopologyMatchers {

    import RuleConversionTest._

    feature("Conversion for rule") {
        scenario("Conversion of a pojo rule to a proto buff is not supported") {
            val simRule = new LiteralRule(new Condition,
                                          RuleResult.Action.ACCEPT,
                                          UUID.randomUUID() /* chainId */)

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.toProto(simRule, classOf[Rule])
            }
        }

        scenario("Protobuf with no conditions results in a rule with a " +
                 "condition that matches everything") {
            val rule = createLiteralRuleBuilder(id = UUID.randomUUID(),
                              chainId = Some(UUID.randomUUID()),
                              Some(Action.ACCEPT))
                           .build
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule.getCondition shouldBe Condition.TRUE
        }

        scenario("Test conversion for a literal rule") {
            val builder = createLiteralRuleBuilder(id = UUID.randomUUID(),
                              chainId = Some(UUID.randomUUID()),
                              Some(Action.ACCEPT))
            setConditionAllFieldsDefault(builder)
            val rule = builder.build
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a trace rule") {
            val builder = createTraceRuleBuilder(id = UUID.randomUUID(),
                              chainId = Some(UUID.randomUUID()))
            setConditionAllFieldsDefault(builder)
            val rule = builder.build()
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a jump rule") {
            val builder = createJumpRuleBuilder(id = UUID.randomUUID(),
                              chainId = Some(UUID.randomUUID()),
                              jumpChainId = Some(UUID.randomUUID()))
            setConditionAllFieldsDefault(builder)
            val rule = builder.build()
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a forward NAT rule") {
            val builder = createNatRuleBuilder(id = UUID.randomUUID,
                                               chainId = Some(UUID.randomUUID),
                                               dnat = Some(false),
                                               targets = Set(createNatTarget()))
            setConditionAllFieldsDefault(builder, matchForwardFlow = Some(true))
            val rule = builder.build()
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a floating IP") {
            val natTarget = createNatTarget(IPAddressUtil.toProto("192.168.0.1"),
                                            IPAddressUtil.toProto("192.168.0.1"),
                                            portStart = 0,
                                            portEnd = 0)
            val builder = createNatRuleBuilder(id = UUID.randomUUID,
                                               chainId = Some(UUID.randomUUID),
                                               targets = Set(natTarget))
            setConditionAllFieldsDefault(builder, matchForwardFlow = Some(true))
            val rule = builder.build()
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule.asInstanceOf[StaticForwardNatRule]
                .getTargetIpAddr shouldBe "192.168.0.1"
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for a reverse NAT rule") {
            val builder = createNatRuleBuilder(id = UUID.randomUUID(),
                                               chainId = Some(UUID.randomUUID()),
                                               dnat = Some(true),
                                               targets = Set(createNatTarget()))
            setConditionAllFieldsDefault(builder, matchForwardFlow = Some(false))

            val rule = builder.build()
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }

        scenario("Test conversion for NAT64 rule") {
            val rule = createNat64Rule(portAddress = Some(randomIPv4Subnet),
                                       natPool = Some(createNatTarget()))
            val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            simRule shouldBeDeviceOf rule
        }
    }

    feature("Protocol buffer validation") {
        scenario("Test protobuf validation with a rule without an action") {
            val rule = createLiteralRuleBuilder(id = UUID.randomUUID(),
                           chainId = Some(UUID.randomUUID()))
                .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("Test protobuf validation with a jump rule without a JUMP action") {
            val rule = createJumpRuleBuilder(id = UUID.randomUUID(),
                           chainId = Some(UUID.randomUUID()),
                           jumpChainId = Some(UUID.randomUUID()))
                      .setAction(Rule.Action.ACCEPT)
                      .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A forward NAT rule with no targets") {
            val rule = createNatRuleBuilder(id = UUID.randomUUID(),
                                            chainId = Some(UUID.randomUUID()),
                                            dnat = Some(true),
                                            matchFwdFlow = Some(true),
                                            Set.empty)
                .setCondition(TopologyCondition.newBuilder
                                  .setMatchForwardFlow(true))
                .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A NAT64 rule with no port address") {
            val rule = createNat64Rule(natPool = Some(createNatTarget()))
            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A NAT64 rule with no NAT pool") {
            val rule = createNat64Rule(portAddress = Some(randomIPv4Subnet))
            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }

        scenario("A trace rule with an action different than CONTINUE") {
            val rule = createTraceRuleBuilder(id = UUID.randomUUID(),
                                              chainId = Some(UUID.randomUUID()))
                .setAction(Action.JUMP)
                .build()

            intercept[ZoomConvert.ConvertException] {
                ZoomConvert.fromProto(rule, classOf[SimRule])
            }
        }
    }

    private def setConditionAllFieldsDefault(ruleBuilder: Rule.Builder,
                                             conjunctionInv: Boolean = true,
                                             matchForwardFlow: Option[Boolean] = None,
                                             inPortIds: Set[UUID] = Set(UUID.randomUUID()),
                                             inPortInv: Boolean = true,
                                             outPortIds: Set[UUID] = Set(UUID.randomUUID()),
                                             outPortInv: Boolean = true,
                                             portGroup: UUID = UUID.randomUUID(),
                                             invPortGroup: Boolean = true,
                                             ipAddrGroupIdSrc: UUID = UUID.randomUUID(),
                                             invIpAddrGroupIdSrc: Boolean = true,
                                             ipAddrGroupIdDst: UUID = UUID.randomUUID(),
                                             invIpAddrGroupIdDst: Boolean = true,
                                             etherType: Int = random.nextInt(),
                                             invDlType: Boolean = true,
                                             ethSrc: MAC = MAC.random,
                                             ethSrcMask: Long = Condition.NO_MASK,
                                             invDlSrc: Boolean = true,
                                             ethDst: MAC = MAC.random,
                                             dlDstMask: Long = Condition.NO_MASK,
                                             invDlDst: Boolean = true,
                                             nwTos: Byte =
                                             random.nextInt(128).asInstanceOf[Byte],
                                             nwTosInv: Boolean = true,
                                             nwProto: Byte =
                                             random.nextInt(128).asInstanceOf[Byte],
                                             nwProtoInv: Boolean = true,
                                             nwSrcIp: IPSubnet[_] = randomIPv4Subnet,
                                             nwDstIp: IPSubnet[_] = randomIPv4Subnet,
                                             tpSrc: Range[Integer] = randomPortRange,
                                             tpDst: Range[Integer] = randomPortRange,
                                             nwSrcInv: Boolean = true,
                                             nwDstInv: Boolean = true,
                                             tpSrcInv: Boolean = true,
                                             tpDstInv: Boolean = true,
                                             traversedDevice: UUID = UUID.randomUUID,
                                             traversedDeviceInv: Boolean = true,
                                             fragmentPolicy: FragmentPolicy =
                                                 FragmentPolicy.ANY,
                                             vlan: Int = random.nextInt(),
                                             noVlan: Boolean = random.nextBoolean())
    : Rule.Builder = {
        val builder = ruleBuilder.getConditionBuilder

        if (matchForwardFlow.isDefined) {
            builder.setMatchForwardFlow(matchForwardFlow.get)
            builder.setMatchReturnFlow(!matchForwardFlow.get)
        }
        builder
            .setConjunctionInv(conjunctionInv)
            .addAllInPortIds(inPortIds.map(_.asProto).asJava)
            .setInPortInv(inPortInv)
            .addAllOutPortIds(outPortIds.map(_.asProto).asJava)
            .setOutPortInv(outPortInv)
            .setPortGroupId(portGroup.asProto)
            .setInvPortGroup(invPortGroup)
            .setIpAddrGroupIdSrc(ipAddrGroupIdSrc.asProto)
            .setInvIpAddrGroupIdSrc(invIpAddrGroupIdSrc)
            .setIpAddrGroupIdDst(ipAddrGroupIdDst.asProto)
            .setInvIpAddrGroupIdDst(invIpAddrGroupIdDst)
            .setDlType(etherType)
            .setInvDlType(invDlType)
            .setDlSrc(ethSrc.toString)
            .setDlSrcMask(ethSrcMask)
            .setInvDlSrc(invDlSrc)
            .setDlDst(ethDst.toString)
            .setDlDstMask(dlDstMask)
            .setInvDlDst(invDlDst)
            .setNwTos(nwTos)
            .setNwTosInv(nwTosInv)
            .setNwProto(nwProto)
            .setNwProtoInv(nwProtoInv)
            .setNwSrcIp(IPSubnetUtil.toProto(nwSrcIp))
            .setNwDstIp(IPSubnetUtil.toProto(nwDstIp))
            .setTpSrc(RangeUtil.toProto(tpSrc))
            .setTpDst(RangeUtil.toProto(tpDst))
            .setNwSrcInv(nwSrcInv)
            .setNwDstInv(nwDstInv)
            .setTpSrcInv(tpSrcInv)
            .setTpDstInv(tpDstInv)
            .setTraversedDevice(traversedDevice.asProto)
            .setTraversedDeviceInv(traversedDeviceInv)
            .setFragmentPolicy(TopologyCondition.FragmentPolicy.valueOf(fragmentPolicy.name))
            .setVlan(vlan)
            .setNoVlan(noVlan)
        ruleBuilder
    }
}

object RuleConversionTest {
    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

    def randomPortRange = {
        val start = random.nextInt(65535)
        val end = start + random.nextInt(65536 - start)
        new Range[Integer](start, end)
    }
}
