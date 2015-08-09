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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{Condition, Int32Range, UUID}
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Neutron.{NeutronFirewall, NeutronFirewallRule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class FirewallTranslator(protected val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronFirewall]
            with ChainManager with RuleManager {
    import FirewallTranslator._

    private def firewallChains(fwId: UUID) =
        List(newChain(inChainId(fwId), preRouteChainName(fwId)),
             newChain(outChainId(fwId), postRouteChainName(fwId)))

    private def ingressRules(fw: NeutronFirewall): List[Rule] = {

        val chainId = inChainId(fw.getId)
        val rules = new ListBuffer[Rule]

        // If admin state is down, add a RETURN rule
        if (!fw.getAdminStateUp) {
            rules += returnRule(inChainAdminStateRule(chainId), chainId)
        }

        // Match on return
        rules += returnFlowRule(inChainFwReturnRule(chainId), chainId)

        // Copy over the enabled rules
        rules ++= fw.getFirewallRuleListList.asScala
            .filter(_.getEnabled).map(r => toPreRoutingRule(r, chainId))

        // Drop rule at the end of inbound
        rules += dropRule(inChainFwDropRule(chainId), chainId)

        rules.toList
    }

    private def egressRules(fw: NeutronFirewall): List[Rule] = {

        val chainId = outChainId(fw.getId)
        val rules = new ListBuffer[Rule]

        // If admin state is down, add a RETURN rule
        if (!fw.getAdminStateUp) {
            rules += returnRule(outChainAdminStateRule(chainId), chainId)
        }

        // Just match forward flow
        rules += forwardFlowRule(outChainFwForwardRule(chainId), chainId)

        rules.toList
    }

    private def translateRulesUpdate(fw: NeutronFirewall,
                                     chainId: UUID => UUID,
                                     translate: NeutronFirewall => List[Rule])
    : MidoOpList = {

        val ops = new MidoOpListBuffer

        val chain = storage.get(classOf[Chain], chainId(fw.getId)).await()
        val oldRules = storage.getAll(classOf[Rule],
                                      chain.getRuleIdsList.asScala).await()
        val newRules = translate(fw)

        val (addedRules, updatedRules, removedIds) = getRuleChanges(
            oldRules.toList, newRules.toList)

        ops ++= removedIds.map(Delete(classOf[Rule], _))
        ops ++= updatedRules.map(Update(_))
        ops ++= addedRules.map(Create(_))

        val cBuilder = chain.toBuilder.clearRuleIds()
        newRules.foreach(r => cBuilder.addRuleIds(r.getId))
        ops += Update(cBuilder.build())

        ops.toList
    }

    private def translateRouterAssocs(fw: NeutronFirewall): MidoOpList = {

        val ops = new MidoOpListBuffer

        // Attach the chains to the routers
        fw.getAddRouterIdsList.asScala foreach { rId =>

            // Add FW jump rules to these chains
            ops += Create(jumpRule(inChainFwJumpRule(rId), inChainId(rId),
                                   inChainId(fw.getId)))
            ops += Create(jumpRule(outChainFwJumpRule(rId), outChainId(rId),
                                   outChainId(fw.getId)))
        }

        // Detach the chains from the routers
        fw.getDelRouterIdsList.asScala foreach { rId =>

            // Remove the FW jump rules from the router
            ops += Delete(classOf[Chain], inChainFwJumpRule(rId))
            ops += Delete(classOf[Chain], outChainFwJumpRule(rId))
        }

        ops.toList
    }

    override protected def translateCreate(fw: NeutronFirewall) =
        firewallChains(fw.getId).map(Create(_)) ++
        ingressRules(fw).map(Create(_)) ++
        egressRules(fw).map(Create(_)) ++
        translateRouterAssocs(fw)

    override protected def translateDelete(id: UUID) =
        firewallChains(id).map(c => Delete(classOf[Chain], c.getId))

    override protected def translateUpdate(fw: NeutronFirewall) =
        translateRulesUpdate(fw, inChainId, ingressRules) ++
        translateRulesUpdate(fw, outChainId, egressRules) ++
        translateRouterAssocs(fw)
}

object FirewallTranslator {

    def preRouteChainName(id: UUID) = "OS_FW_PRE_ROUTING_" + id.asJava

    def postRouteChainName(id: UUID) = "OS_FW_POST_ROUTING_" + id.asJava

    def inChainAdminStateRule(chainId: UUID): UUID =
        chainId.xorWith(0x46c4206a41b011e5L, 0x878b0242ac110002L)

    def outChainAdminStateRule(chainId: UUID): UUID =
        chainId.xorWith(0x67f6747241b011e5L, 0x8f100242ac110002L)

    def inChainFwJumpRule(routerId: UUID): UUID =
        routerId.xorWith(0xc309e08c3eaf11e5L, 0xaf410242ac110002L)

    def outChainFwJumpRule(routerId: UUID): UUID =
        routerId.xorWith(0x1b4b62ca3eb011e5L, 0x91260242ac110002L)

    def inChainFwDropRule(chainId: UUID): UUID =
        chainId.xorWith(0xae3a0312402611e5L, 0xab660242ac110002L)

    def inChainFwReturnRule(chainId: UUID): UUID =
        chainId.xorWith(0xdac28d1e402611e5L, 0xa59c0242ac110002L)

    def outChainFwForwardRule(chainId: UUID): UUID =
        chainId.xorWith(0xfbcb6f3a402611e5L, 0xb8cc0242ac110002L)

    def toInt32Range(portRange: String): Int32Range = {
        // portRange could be a single value or a two values delimited by ':'
        val range = portRange.split(':')
        range.length match {
            case 1 =>
                Int32Range.newBuilder
                    .setStart(range(0).toInt)
                    .setEnd(range(0).toInt).build()
            case 2 =>
                Int32Range.newBuilder
                    .setStart(range(0).toInt)
                    .setEnd(range(1).toInt).build()
            case _ =>
                throw new IllegalArgumentException(
                    "Invalid port range " + portRange)
        }
    }

    def toTopologyRuleAction(action: FirewallRuleAction): Action = {

        action match {

            case FirewallRuleAction.ALLOW =>
                Action.RETURN
            case FirewallRuleAction.DENY =>
                Action.DROP
            case _ =>
                throw new IllegalArgumentException(
                    "Unsupported firewall rule action " + action)
        }
    }

    def toPreRoutingRule(r: NeutronFirewallRule, chainId: UUID): Rule = {

        // IPv6 not supported
        if (r.getIpVersion == 6) {
            throw new IllegalArgumentException(
                "IPv6 not supported in firewall rule.")
        }

        val cond = Condition.newBuilder()
        if (r.hasProtocol) {
            cond.setNwProto(r.getProtocol.getNumber)
        }

        if (r.hasSourceIpAddress) {
            cond.setNwSrcIp(r.getSourceIpAddress)
        }

        if (r.hasDestinationIpAddress) {
            cond.setNwDstIp(r.getDestinationIpAddress)
        }

        if (r.hasSourcePort) {
            cond.setTpSrc(toInt32Range(r.getSourcePort))
        }

        if (r.hasDestinationPort) {
            cond.setTpDst(toInt32Range(r.getDestinationPort))
        }

        Rule.newBuilder()
            .setId(r.getId)
            .setChainId(chainId)
            .setCondition(cond.build())
            .setAction(toTopologyRuleAction(r.getAction)).build()
    }
}