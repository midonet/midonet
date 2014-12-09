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

import scala.collection.mutable.ListBuffer

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{Int32Range, RuleDirection, UUID}
import org.midonet.cluster.models.Neutron.{SecurityGroupRule, SecurityGroup}
import org.midonet.cluster.models.Topology.{Rule, IpAddrGroup, Chain}
import org.midonet.cluster.services.c3po._
import org.midonet.cluster.util.IPSubnetUtil

import org.midonet.cluster.util.UUIDUtil.toProto
import java.util.{UUID => JUUID}

import scala.collection.JavaConverters._

import org.midonet.packets.IPSubnet

class SecurityGroupTranslator(storage: ReadOnlyStorage)
    extends NeutronApiTranslator[SecurityGroup](classOf[SecurityGroup],
                                                storage) {
    import SecurityGroupTranslator._

    /**
     * Converts an operation on an external model into 1 or more corresponding
     * operations on internal MidoNet models.
     */
    override def toMido(op: C3POOp[SecurityGroup]):
            List[MidoModelOp[_ <: Object]] = op match {
        case C3POCreate(sg) => translateCreate(sg)
        case C3POUpdate(sg) => translateUpdate(sg)
        case C3PODelete(_, id) => translateDelete(id)
    }

    private def translateCreate(sg: SecurityGroup):
            List[MidoModelOp[_ <: Object]] = {
        val ops = new ListBuffer[MidoModelOp[_ <: Object]]

        // We're going to create two chains for a security group, representing
        // ingress and egress directions.  Use the name field to associate them back to SG.
        // Note that 'ingress' is 'outbound' in midonet. A Neutron "ingress"
        // rule is for traffic going into the VM, whereas a Midonet "outbound"
        // chain is for traffic going out of Midonet (and into a VM port).
        val outboundChain = createChain(sg, ingressChainName)
        val inboundChain = createChain(sg, egressChainName)

        val ipAddrGroup = createIpAddrgroup(sg, inboundChain.getId,
                                            outboundChain.getId)

        val neutronRules = sg.getSecurityGroupRulesList.asScala
        val (neutronIngressRules, neutronEgressRules) =
            neutronRules.partition(_.getDirection == RuleDirection.INGRESS)
        val (ingressRules, ingressRuleIds) =
            neutronIngressRules.map(sgr => (translateRule(sgr), sgr.getId))
        val (egressRules, egressRuleIds) =
            neutronEgressRules.map(sgr => (translateRule(sgr), sgr.getId))



        ops += MidoCreate(outboundChain)
        ops += MidoCreate(inboundChain)
        ops += MidoCreate(ipAddrGroup)
        ops.toList
    }

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
                            nameMaker: UUID => String,
                            ruleIds: Seq[UUID]): Chain = {
        // TODO: Tenant ID isn't in the topology object. Should it be?
        Chain.newBuilder
            .setId(toProto(JUUID.randomUUID))
            .setName(nameMaker(sg.getId))
            .addAllRuleIds(ruleIds.asJava)
            .build()
    }

    private def createIpAddrgroup(sg: SecurityGroup,
                                  inboundChainId: UUID,
                                  outboundChainId: UUID): IpAddrGroup = {
        IpAddrGroup.newBuilder
            .setId(sg.getId)
            .setName(sg.getName)
            .setInboundChainId(inboundChainId)
            .setOutboundChainId(outboundChainId)
            .build()
    }


    private def translateUpdate(sg: SecurityGroup):
            List[MidoModelOp[_ <: Object]] = {
        List()
    }

    private def translateDelete(id: Commons.UUID):
            List[MidoModelOp[_ <: Object]] = {
        List()
    }
}

object SecurityGroupTranslator {
    def ingressChainName(sgId: UUID) =
        "OS_SG_" + sgId + "_" + RuleDirection.INGRESS

    def egressChainName(sgId: UUID) =
        "OS_SG_" + sgId + "_" + RuleDirection.EGRESS
}