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

import org.midonet.cluster.models.Commons.{Int32Range, RuleDirection}
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Topology.{Condition, Rule}
import org.midonet.cluster.util.IPSubnetUtil

/**
 * Contains rule-related operations shared by multiple translators.
 */
object SecurityGroupRuleManager extends ChainManager {
  /**
   * Translate a Neutron SecurityGroupRule to a Midonet Rule.
   */
    def translate(sgRule: SecurityGroupRule): Rule = {
        val bldr = Rule.newBuilder
            .setId(sgRule.getId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.ACCEPT)

        val condBldr = Condition.newBuilder
        if (sgRule.hasProtocol)
            condBldr.setNwProto(sgRule.getProtocol.getNumber)
        if (sgRule.hasEthertype)
            condBldr.setDlType(sgRule.getEthertype.getNumber)
        if (sgRule.hasPortRangeMin)
            condBldr.setTpDst(createRange(sgRule.getPortRangeMin,
                                      sgRule.getPortRangeMax))

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
        bldr.setCondition(condBldr).build()
    }

    private def createRange(start: Int, end: Int): Int32Range = {
        Int32Range.newBuilder.setStart(start).setEnd(end).build()
    }
}
