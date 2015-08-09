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

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Topology.{Chain, IPAddrGroup, Rule}
import org.midonet.cluster.services.c3po.midonet._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.StringUtil.indent
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[SecurityGroup] with ChainManager
            with RuleManager {
    import org.midonet.cluster.services.c3po.translators.SecurityGroupTranslator._

    private case class TranslatedSecurityGroup(
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

        // Get Neutron rules and divide into egress and ingress rules.
        val neutronRules = sg.getSecurityGroupRulesList.asScala.toList
        val (neutronIngressRules, neutronEgressRules) =
            neutronRules.partition(_.getDirection == RuleDirection.INGRESS)

        val outboundRules = neutronIngressRules.map(
            SecurityGroupRuleManager.translate)
        val inboundRules = neutronEgressRules.map(
            SecurityGroupRuleManager.translate)

        val inboundChainId = inChainId(sg.getId)
        val outboundChainId = outChainId(sg.getId)

        val inboundChain = newChain(inboundChainId, egressChainName(sg.getId))
        val outboundChain = newChain(outboundChainId,
                                     ingressChainName(sg.getId))

        val ipAddrGroup = createIpAddrGroup(sg, inboundChainId, outboundChainId)

        val xlated = TranslatedSecurityGroup(ipAddrGroup,
                                             inboundChain, inboundRules,
                                             outboundChain, outboundRules)
        log.trace("Translated security group {} to: \n{}",
                  Array(sg.getId, xlated):_*)

        xlated
    }

    protected override def translateCreate(sg: SecurityGroup)
    : List[MidoOp[_ <: Message]] = {

        val translatedSg = translate(sg)

        val ops = new ListBuffer[MidoOp[_ <: Message]]

        ops += Create(translatedSg.inboundChain)
        ops += Create(translatedSg.outboundChain)
        ops ++= translatedSg.inboundRules.map(Create(_))
        ops ++= translatedSg.outboundRules.map(Create(_))
        ops += Create(translatedSg.ipAddrGroup)
        ops.toList
    }

    protected override def translateUpdate(newSg: SecurityGroup)
    : List[MidoOp[_ <: Message]] = {
        // There's a one-to-many relationship between Neutron security groups
        // and Midonet rules, so an update to a Neutron security group can
        // correspond to creation, update, and/or deletion of Midonet rules.
        // To determine which rules need to be created, updated, and deleted,
        // we need to look at the old version of the SecurityGroup to determine
        // which rules to create, update, delete, or leave alone.
        val oldSg = storage.get(classOf[SecurityGroup], newSg.getId).await()
        val xltOldSg = translate(oldSg)
        val xltNewSg = translate(newSg)

        getRuleChangeOps(
            xltOldSg.inboundRules ++ xltOldSg.outboundRules,
            xltNewSg.inboundRules ++ xltNewSg.outboundRules)
    }

    protected override def translateDelete(sgId: UUID)
    : List[MidoOp[_ <: Message]] = {
        List(Delete(classOf[Chain], inChainId(sgId)),
             Delete(classOf[Chain], outChainId(sgId)),
             Delete(classOf[IPAddrGroup], sgId))
    }

    private def getRuleChangeOps(oldRules: List[Rule], newRules: List[Rule])
    : List[MidoOp[Rule]] = {

        val (addedRules, updatedRules, removedIds) = getRuleChanges(oldRules,
                                                                    newRules)
        removedIds.map(Delete(classOf[Rule], _)) ++
        updatedRules.map(Update(_)) ++
        addedRules.map(Create(_))
    }

    private def createIpAddrGroup(sg: SecurityGroup,
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

object SecurityGroupTranslator {
    def ingressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + UUIDUtil.toString(sgId) + "_" + RuleDirection.EGRESS
}
