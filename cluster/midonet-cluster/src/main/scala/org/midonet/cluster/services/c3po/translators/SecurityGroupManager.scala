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

import org.midonet.cluster.models.Neutron.{SecurityGroupRule, SecurityGroup}
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Topology.{Rule, IPAddrGroup, Chain}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.StringUtil._

import org.slf4j.Logger

import scala.collection.JavaConverters._


/**
 * Contains chain-related operations shared by multiple translators.
 */
trait SecurityGroupManager {

    protected val log: Logger

    case class TranslatedSecurityGroup(
        ipAddrGroup: IPAddrGroup,
        inboundChain: Chain, inboundRules: List[Rule],
        outboundChain: Chain, outboundRules: List[Rule]) {

        override def toString: String = {
            s"ipAddrGroup:\n${indent(ipAddrGroup, 4)}\n" +
            s"inboundChain:\n${indent(inboundChain, 4)}\n" +
            s"inboundRules:\n${toString(inboundRules)}\n" +
            s"outboundChain:\n${indent(outboundChain, 4)}\n" +
            s"outboundRules:\n${toString(outboundRules)}\n"
        }

        private def toString(rules: Seq[Rule]): String = {
            rules.map(indent(_, 4)).mkString("\n")
        }
    }

    def translate(sg: SecurityGroup): TranslatedSecurityGroup = {
        // A Neutron SecurityGroup translates to an IPAddrGroup and two chains,
        // containing the rules for ingress and egress. Note that 'ingress' is
        // 'outbound' from Midonet's perspective. A Neutron "ingress" rule is
        // for traffic going into the VM, whereas a Midonet "outbound" chain is
        // for traffic going out of Midonet (and into a VM port), and vice-versa
        // for egress and inbound.
        if (log.isTraceEnabled) {
            log.trace("Translating security group:\n{}", indent(sg, 4))
        }

        // Get Neutron rules and divide into egress and ingress rules.
        val neutronRules = sg.getSecurityGroupRulesList.asScala.toList
        val (neutronIngressRules, neutronEgressRules) =
            neutronRules.partition(_.getDirection == RuleDirection.INGRESS)

        val outboundRules = neutronIngressRules.map(SecurityGroupRuleManager.translate)
        val outboundRuleIds = neutronIngressRules.map(_.getId)
        val outboundChainId = ChainManager.outChainId(sg.getId)
        val outboundChain = createChain(
            sg, outboundChainId, ingressChainName(sg.getId), outboundRuleIds)

        val inboundRules = neutronEgressRules.map(SecurityGroupRuleManager.translate)
        val inboundRuleIds = neutronEgressRules.map(_.getId)
        val inboundChainId = ChainManager.inChainId(sg.getId)
        val inboundChain = createChain(
            sg, inboundChainId, egressChainName(sg.getId), inboundRuleIds)

        val ipAddrGroup = createIpAddrGroup(sg, inboundChainId, outboundChainId)

        val xlated = TranslatedSecurityGroup(ipAddrGroup, inboundChain,
                                             inboundRules, outboundChain,
                                             outboundRules)
        log.trace("Translated security group {} to: \n{}", Array(sg.getId, xlated):_*)

        xlated
    }

    def ingressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.EGRESS

    def createChain(sg: SecurityGroup, chainId: UUID, name: String,
                            ruleIds: Seq[UUID]): Chain = {
        // TODO: Tenant ID isn't in the topology object. Should it be?
        Chain.newBuilder()
            .setId(chainId)
            .setName(name)
            .addAllRuleIds(ruleIds.asJava)
            .build()
    }

    def createIpAddrGroup(sg: SecurityGroup,
                          inboundChainId: UUID,
                          outboundChainId: UUID): IPAddrGroup = {
        IPAddrGroup.newBuilder
            .setId(sg.getId)
            .setName(sg.getName)
            .setInboundChainId(inboundChainId)
            .setOutboundChainId(outboundChainId)
            .build()
    }
}
