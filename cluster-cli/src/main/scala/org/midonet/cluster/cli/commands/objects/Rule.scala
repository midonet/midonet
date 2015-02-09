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

package org.midonet.cluster.cli.commands.objects

import java.lang.reflect.Type
import java.util.UUID

import org.midonet.cluster.cli.commands.objects.Rule.{NatTarget, NatTargetConverter, NatRuleFactory, RuleFactory}
import org.midonet.cluster.data.ZoomConvert.Factory
import org.midonet.cluster.data._
import org.midonet.cluster.models.Topology.Rule.{NatTarget => TopologyNatTarget}
import org.midonet.cluster.models.Topology.{Rule => TopologyRule}
import org.midonet.cluster.models.Topology.Rule.Type._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.IPv4Addr

object Rule {
    class RuleFactory extends Factory[Rule, TopologyRule] {
        def getType(proto: TopologyRule): Class[_ <: Rule] = {
            proto.getType match {
                case JUMP_RULE => classOf[JumpRule]
                case LITERAL_RULE => classOf[LiteralRule]
                case TRACE_RULE => classOf[TraceRule]
                case NAT_RULE => classOf[NatRule]
                case _ => classOf[Rule]
            }
        }
    }

    class NatRuleFactory extends Factory[NatRule, TopologyRule] {
        def getType(proto: TopologyRule): Class[_ <: NatRule] = {
            if (proto.hasMatchForwardFlow && proto.getMatchForwardFlow)
                classOf[ForwardNatRule]
            else if (proto.hasMatchReturnFlow && proto.getMatchReturnFlow)
                classOf[ReverseNatRule]
            else classOf[NatRule]
        }
    }

    class NatTarget(val nwStart: IPv4Addr, val nwEnd: IPv4Addr,
                    val tpStart: Int, val tpEnd: Int) {
        override def toString =
            s"nw-start=$nwStart nw-end=$nwEnd tp-start=$tpStart tp-end=$tpEnd"
    }

    class NatTargetConverter
        extends ZoomConvert.Converter[NatTarget, TopologyNatTarget] {
        override def toProto(value: NatTarget, clazz: Type): TopologyNatTarget =
            TopologyNatTarget.newBuilder
                .setNwStart(value.nwStart.asProto)
                .setNwEnd(value.nwEnd.asProto)
                .setTpStart(value.tpStart)
                .setTpEnd(value.tpEnd)
                .build()

        override def fromProto(value: TopologyNatTarget, clazz: Type): NatTarget = {
            new NatTarget(value.getNwStart.asIPv4Address,
                          value.getNwEnd.asIPv4Address,
                          value.getTpStart, value.getTpEnd)
        }
    }
}

/** A chain rule. */
@ZoomClass(clazz = classOf[TopologyRule], factory = classOf[RuleFactory])
@CliName(name = "rule")
class Rule extends Condition {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "type")
    @CliName(name = "type")
    var ruleType: RuleType = _
    @ZoomField(name = "chain_id", converter = classOf[UUIDConverter])
    @CliName(name = "chain-id", readonly = false)
    var chainId: UUID = _
    @ZoomField(name = "action")
    @CliName(name = "action", readonly = false)
    var action: RuleAction = _
}

/** A chain jump rule. */
@ZoomOneOf(name = "jump_rule_data")
@CliName(name = "rule")
class JumpRule extends Rule {

    ruleType = RuleType.JUMP

    @ZoomField(name = "jump_to", converter = classOf[UUIDConverter])
    @CliName(name = "jump-to-chain-id", readonly = false)
    var jumpToChainId: UUID = _
    @ZoomField(name = "jump_chain_name")
    @CliName(name = "jump-to-chain-name", readonly = false)
    var jumpToChainName: String = _
}

/** A chain literal rule. */
class LiteralRule extends Rule {
    ruleType = RuleType.LITERAL
}

/** A chain trace rule. */
class TraceRule extends Rule {
    ruleType = RuleType.TRACE
}

/** A NAT rule. */
@ZoomClass(clazz = classOf[TopologyRule], factory = classOf[NatRuleFactory])
@ZoomOneOf(name = "nat_rule_data")
@CliName(name = "rule")
class NatRule extends Rule {

    ruleType = RuleType.NAT

    @ZoomField(name = "dnat")
    @CliName(name = "dnat", readonly = false)
    var dnat: Boolean = _
}

/** A forward NAT rule. */
class ForwardNatRule extends NatRule {

    matchForwardFlow = true

    @ZoomField(name = "nat_targets", converter = classOf[NatTargetConverter])
    @CliName(name = "targets")
    var targets: Set[NatTarget] = _
}

/** A reverse NAT rule. */
class ReverseNatRule extends NatRule {
    matchReturnFlow = true
}
