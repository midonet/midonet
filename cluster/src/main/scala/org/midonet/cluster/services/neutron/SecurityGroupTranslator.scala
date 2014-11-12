/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.services.neutron

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{Int32Range, RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.{SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.models.Topology.{Chain, IpAddrGroup, Rule}
import org.midonet.cluster.services.c3po._
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.richProtoUuid
import org.midonet.util.StringUtils.indent

class SecurityGroupTranslator(storage: ReadOnlyStorage)
    extends NeutronApiTranslator[SecurityGroup](classOf[SecurityGroup],
                                                storage) {
    import org.midonet.cluster.services.neutron.SecurityGroupTranslator._

    private val STORAGE_TIMEOUT = Duration(2, TimeUnit.SECONDS)

    /**
     * Converts an operation on an external model into 1 or more corresponding
     * operations on internal Midonet models.
     */
    override def toMido(op: C3POOp[SecurityGroup]):
            List[MidoModelOp[_ <: Object]] = op match {
        case C3POCreate(sg) => translateCreate(sg)
        case C3POUpdate(sg) => translateUpdate(sg)
        case C3PODelete(_, id) => translateDelete(id)
    }

    private case class TranslatedSecurityGroup(
        ipAddrGroup: IpAddrGroup,
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

    private def translate(sg: SecurityGroup): TranslatedSecurityGroup = {
        // A Neutron SecurityGroup translates to an IPAddrGroup and two chains,
        // containing the rules for ingress and egress. Note that 'ingress' is
        // 'outbound' from Midonet's perspective. A Neutron "ingress" rule is
        // for traffic going into the VM, whereas a Midonet "outbound" chain is
        // for traffic going out of Midonet (and into a VM port), and vice-versa
        // for egress and inbound.
        if (log.isTraceEnabled) {
            log.trace("Translating security group:\n{}", indent(sg, 4))
        }

        val neutronRules = sg.getSecurityGroupRulesList.asScala.toList
        val (neutronIngressRules, neutronEgressRules) =
            neutronRules.partition(_.getDirection == RuleDirection.INGRESS)

        val outboundRules = neutronIngressRules.map(translateRule)
        val outboundRuleIds = neutronIngressRules.map(_.getId)
        val inboundRules = neutronEgressRules.map(translateRule)
        val inboundRuleIds = neutronEgressRules.map(_.getId)

        val inboundChainId = sg.getId.nextUuid
        val outboundChainId = inboundChainId.nextUuid

        val inboundChain = createChain(sg, inboundChainId,
                                       egressChainName, inboundRuleIds)
        val outboundChain = createChain(sg, outboundChainId,
                                        ingressChainName, outboundRuleIds)

        val ipAddrGroup = createIpAddrGroup(sg, inboundChainId, outboundChainId)

        val xlated = TranslatedSecurityGroup(ipAddrGroup,
                                             inboundChain, inboundRules,
                                             outboundChain, outboundRules)
        log.trace("Translated security group {} to: \n{}",
                  Array(sg.getId, xlated):_*)

        xlated
    }

    private def translateCreate(sg: SecurityGroup)
            : List[MidoModelOp[_ <: Object]] = {

        val translatedSg = translate(sg)

        val ops = new ListBuffer[MidoModelOp[_ <: Object]]

        for (rule <- translatedSg.inboundRules) ops += MidoCreate(rule)
        for (rule <- translatedSg.outboundRules) ops += MidoCreate(rule)
        ops += MidoCreate(translatedSg.inboundChain)
        ops += MidoCreate(translatedSg.outboundChain)
        ops += MidoCreate(translatedSg.ipAddrGroup)
        ops.toList
    }

    private def translateUpdate(newSg: SecurityGroup)
            : List[MidoModelOp[_ <: Object]] = {
        // There's a one-to-many relationship between Neutron security groups
        // and Midonet rules, so an update to a Neutron security group can
        // correspond to creation, update, and/or deletion of Midonet rules.
        // To determine which rules need to be created, updated, and deleted,
        // we need to look at the old version of the SecurityGroup to determine
        // which rules to create, update, delete, or leave alone.
        val oldSg = await(storage.get(classOf[SecurityGroup], newSg.getId))
        val xltOldSg = translate(oldSg)
        val xltNewSg = translate(newSg)

        val inboundRuleChangeOps = getRuleChangeOps(xltOldSg.inboundRules,
                                                    xltNewSg.inboundRules)
        val outboundRuleChangeOps = getRuleChangeOps(xltOldSg.outboundRules,
                                                     xltNewSg.outboundRules)

        val ops = new ListBuffer[MidoModelOp[_ <: Object]]
        ops ++= inboundRuleChangeOps
        ops ++= outboundRuleChangeOps
        if (xltNewSg.ipAddrGroup != xltOldSg.ipAddrGroup)
            ops += MidoUpdate(xltNewSg.ipAddrGroup)
        if (xltNewSg.inboundChain != xltOldSg.inboundChain)
            ops += MidoUpdate(xltNewSg.inboundChain)
        if (xltNewSg.outboundChain != xltOldSg.outboundChain)
            ops += MidoUpdate(xltNewSg.outboundChain)
        ops.toList
    }

    private def translateDelete(id: UUID): List[MidoModelOp[_ <: Object]] = {
        // Topology store should be configured to allow this delete to cascade
        // to the chains and rules.
        List(MidoDelete(classOf[IpAddrGroup], id))
    }

    /**
     * Gets the operations necessary to replace oldRules with newRules in the
     * topology store. Will generate operations to:
     *
     * 1. Delete rules which are in oldRules but not in newRules.
     * 2. Update rules which are in both lists but have changed in some way.
     * 3. Create rules which are in newRules but not in oldRules.
     *
     * A rule in one list is considered also to be in the other list if the
     * other list contains a rule with the same ID.
     *
     * No operations are generated for rules which are identical in both lists.
     */
    private def getRuleChangeOps(oldRules: List[Rule], newRules: List[Rule])
            : List[MidoModelOp[Rule]] = {
        val oldRuleIds = oldRules.map(_.getId)
        val newRuleIds = newRules.map(_.getId)

        val removedIds = oldRuleIds.diff(newRuleIds)
        val addedIds = newRuleIds.diff(oldRuleIds)
        val (keptRules, addedRules) = newRules.partition(addedIds.contains(_))

        // No need to update rules that aren't changing.
        val updatedRules = keptRules.diff(oldRules)

        val ops = new ListBuffer[MidoModelOp[Rule]]()
        ops.appendAll(removedIds.map(MidoDelete(classOf[Rule], _)))
        ops.appendAll(updatedRules.map(MidoUpdate(_)))
        ops.appendAll(addedRules.map(MidoCreate(_)))
        ops.toList
    }

    /**
     * Translate a Neutron SecurityGroupRule to a Midonet Rule.
     */
    private def translateRule(sgRule: SecurityGroupRule): Rule = {
        val bldr = Rule.newBuilder
        bldr.setId(sgRule.getId)
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

    private def createChain(sg: SecurityGroup,
                            chainId: UUID,
                            nameMaker: UUID => String,
                            ruleIds: Seq[UUID]): Chain = {
        // TODO: Tenant ID isn't in the topology object. Should it be?
        Chain.newBuilder
            .setId(chainId)
            .setName(nameMaker(sg.getId))
            .addAllRuleIds(ruleIds.asJava)
            .build()
    }

    private def createIpAddrGroup(sg: SecurityGroup,
                                  inboundChainId: UUID,
                                  outboundChainId: UUID): IpAddrGroup = {
        IpAddrGroup.newBuilder
            .setId(sg.getId)
            .setName(sg.getName)
            .setInboundChainId(inboundChainId)
            .setOutboundChainId(outboundChainId)
            .build()
    }

    private def await[T](ft: Future[T]): T = Await.result(ft, STORAGE_TIMEOUT)
}

object SecurityGroupTranslator {
    def ingressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.EGRESS
}