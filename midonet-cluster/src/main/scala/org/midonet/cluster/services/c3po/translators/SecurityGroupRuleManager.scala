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

import org.midonet.cluster.models.Commons.{Condition, Int32Range, Protocol, RuleDirection, UUID}
import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

/**
 * Contains rule-related operations shared by multiple translators.
 */
object SecurityGroupRuleManager extends ChainManager {
    /**
     * Translate a Neutron SecurityGroupRule to a Midonet Rule.
     */
    def translate(sgRule: SecurityGroupRule): List[Rule] = {
        if (sgRule.hasPortRangeMin || sgRule.hasPortRangeMax) {
            // We translate a L4 SG rule into two low-level rules.
            // One for Header, another for NonHeader.
            List(createHeaderRule(sgRule), createNonHeaderRule(sgRule))
        } else {
            List(createAnyRule(sgRule))
        }
    }

    /** Deterministically generate NonHeader rule ID from the primary ID. */
    def nonHeaderRuleId(ruleId: UUID): UUID =
        ruleId.xorWith(0x933733b5db0f5cceL, 0xeaa631e8b0b34cefL)

    private def createHeaderRule(sgRule: SecurityGroupRule): Rule = {
        val bldr = commonBuilder(sgRule)
        val condBldr = bldr.getConditionBuilder

        condBldr.setFragmentPolicy(FragmentPolicy.HEADER)
        if (isIcmp(sgRule)) {
            // For ICMP, portRangeMin is the ICMP type, and portRangeMax is
            // the ICMP code.  They are translated as:
            //     type: Range<portRangeMin, portRangeMin>
            //     code: Range<portRangeMax, portRangeMax>
            if (sgRule.hasPortRangeMin) {
                condBldr.setTpSrc(createRange(sgRule.getPortRangeMin,
                                              sgRule.getPortRangeMin))
            }
            if (sgRule.hasPortRangeMax) {
                condBldr.setTpDst(createRange(sgRule.getPortRangeMax,
                                              sgRule.getPortRangeMax))
            }
        } else {
            condBldr.setTpDst(createRangeFromRule(sgRule))
        }
        bldr.build()
    }

    private def createNonHeaderRule(sgRule: SecurityGroupRule): Rule = {
        val bldr = commonBuilder(sgRule)
        val condBldr = bldr.getConditionBuilder

        condBldr.setFragmentPolicy(FragmentPolicy.NONHEADER)
        bldr.setId(nonHeaderRuleId(sgRule.getId)).build()
    }

    private def createAnyRule(sgRule: SecurityGroupRule): Rule = {
        val bldr = commonBuilder(sgRule)
        val condBldr = bldr.getConditionBuilder

        condBldr.setFragmentPolicy(FragmentPolicy.ANY)
        bldr.build()
    }

    private def commonBuilder(sgRule: SecurityGroupRule): Rule.Builder = {
        val bldr = Rule.newBuilder
            .setId(sgRule.getId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.ACCEPT)
        val condBldr = Condition.newBuilder

        if (sgRule.hasProtocol)
            condBldr.setNwProto(sgRule.getProtocol.getNumber)
        if (sgRule.hasEthertype)
            condBldr.setDlType(sgRule.getEthertype.getNumber)

        if (sgRule.getDirection == RuleDirection.INGRESS) {
            bldr.setChainId(outChainId(sgRule.getSecurityGroupId))
            if (sgRule.hasRemoteIpPrefix)
                condBldr.setNwSrcIp(IPSubnetUtil.toProto(
                    sgRule.getRemoteIpPrefix))
            if (sgRule.hasRemoteGroupId)
                condBldr.setIpAddrGroupIdSrc(sgRule.getRemoteGroupId)
        } else {
            bldr.setChainId(inChainId(sgRule.getSecurityGroupId))
            if (sgRule.hasRemoteIpPrefix)
                condBldr.setNwDstIp(IPSubnetUtil.toProto(
                    sgRule.getRemoteIpPrefix))
            if (sgRule.hasRemoteGroupId)
                condBldr.setIpAddrGroupIdDst(sgRule.getRemoteGroupId)
        }
        bldr.setCondition(condBldr)
        bldr
    }

    private def isIcmp(sgRule: SecurityGroupRule): Boolean =
        sgRule.hasProtocol && sgRule.getProtocol == Protocol.ICMP

    private def createRange(start: Int, end: Int): Int32Range =
        Int32Range.newBuilder.setStart(start).setEnd(end).build()

    private def createRangeFromRule(sgRule: SecurityGroupRule): Int32Range = {
        val builder = Int32Range.newBuilder
        if (sgRule.hasPortRangeMin) builder.setStart(sgRule.getPortRangeMin)
        if (sgRule.hasPortRangeMax) builder.setEnd(sgRule.getPortRangeMax)
        builder.build
    }
}
