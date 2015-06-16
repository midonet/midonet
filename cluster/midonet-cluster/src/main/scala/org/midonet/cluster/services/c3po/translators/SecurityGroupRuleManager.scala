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

import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.util.IPSubnetUtil

import org.midonet.cluster.models.Commons.{Int32Range, RuleDirection}
import org.midonet.cluster.models.Topology.Rule

/**
 * Contains chain-related operations shared by multiple translators.
 */
object SecurityGroupRuleManager {
  /**
   * Translate a Neutron SecurityGroupRule to a Midonet Rule.
   */
    def translate(sgRule: SecurityGroupRule): Rule = {
        val bldr = Rule.newBuilder
            .setId(sgRule.getId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.ACCEPT)

        if (sgRule.hasProtocol)
            bldr.setNwProto(sgRule.getProtocol.getNumber)
        if (sgRule.hasEthertype)
            bldr.setDlType(sgRule.getEthertype.getNumber)
        if (sgRule.hasPortRangeMin)
            bldr.setTpDst(createRange(sgRule.getPortRangeMin,
                                      sgRule.getPortRangeMax))

        if (sgRule.getDirection == RuleDirection.INGRESS) {
            if (sgRule.hasRemoteIpPrefix)
                bldr.setNwSrcIp(IPSubnetUtil.toProto(sgRule.getRemoteIpPrefix))
            if (sgRule.hasRemoteGroupId)
                bldr.setIpAddrGroupIdSrc(sgRule.getRemoteGroupId)
        } else {
            if (sgRule.hasRemoteIpPrefix)
                bldr.setNwDstIp(IPSubnetUtil.toProto(sgRule.getRemoteIpPrefix))
            if (sgRule.hasRemoteGroupId)
                bldr.setIpAddrGroupIdDst(sgRule.getRemoteGroupId)
        }
        bldr.build()
    }

    private def createRange(start: Int, end: Int): Int32Range = {
        Int32Range.newBuilder.setStart(start).setEnd(end).build()
    }
}
