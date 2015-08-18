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
import org.midonet.cluster.models.Commons.{Condition, IPVersion, Int32Range, UUID}
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

    private def preRoutingRules(fw: NeutronFirewall): List[Rule] = {
        val chainId = inChainId(fw.getId)
        val rules = new ListBuffer[Rule]

        // If admin state is down, add a RETURN rule
        if (!fw.getAdminStateUp) {
            rules += returnRule(chainId)
        }

        // Match on return
        rules += returnFlowRule(chainId)

        // Copy over the enabled rules
        rules ++= fw.getFirewallRuleListList.asScala
            .filter(_.getEnabled).map(r => toRule(r, chainId))

        // Drop rule at the end of inbound
        rules += dropRule(chainId)
        rules.toList
    }

    private def postRoutingRules(fw: NeutronFirewall): List[Rule] = {
        val chainId = outChainId(fw.getId)
        val rules = new ListBuffer[Rule]

        // If admin state is down, add a RETURN rule
        if (!fw.getAdminStateUp) {
            rules += returnRule(chainId)
        }

        // Just match forward flow
        rules += forwardFlowRule(chainId)
        rules.toList
    }

    private def translateRuleChainUpdate(fw: NeutronFirewall,
                                         chain: Chain,
                                         generateFirewallChainRules:
                                             NeutronFirewall => List[Rule]): MidoOpList = {
        val ops = new MidoOpListBuffer
        val oldRules = storage.getAll(classOf[Rule],
                                      chain.getRuleIdsList.asScala).await()
        val newRules = generateFirewallChainRules(fw)
        val (addedRules, updatedRules, removedIds) = getRuleChanges(
            oldRules.toList, newRules.toList)

        ops ++= removedIds.map(Delete(classOf[Rule], _))
        ops ++= updatedRules.map(Update(_))
        ops ++= addedRules.map(Create(_))

        // Set the order of the rules on this chain
        val cBuilder = chain.toBuilder.clearRuleIds()
        newRules.foreach(r => cBuilder.addRuleIds(r.getId))
        ops += Update(cBuilder.build())
        ops.toList
    }

    private def translateRouterAssocs(fw: NeutronFirewall): MidoOpList = {
        val ops = new MidoOpListBuffer

        // Neutron guarantees that the router IDs in add-router-ids and
        // del-router-ids do not overlap, so there should never be a case
        // where both delete and create must be performed on the same router.
        // However, since deletion happens before creation, translation should
        // still succeed.

        // Detach the chains from the routers
        fw.getDelRouterIdsList.asScala foreach { rId =>
            // Remove the FW jump rules from the router if they exist
            ops += Delete(classOf[Rule], inChainFwJumpRuleId(rId))
            ops += Delete(classOf[Rule], outChainFwJumpRuleId(rId))
        }

        if (fw.getAddRouterIdsCount > 0) {
            // Attach the chains to the routers
            fw.getAddRouterIdsList.asScala foreach { rId =>
                // Add FW jump rules to these chains
                val inRuleId = inChainFwJumpRuleId(rId)
                if (!storage.exists(classOf[Rule], inRuleId).await()) {
                    val chain = storage.get(classOf[Chain],
                                            inChainId(rId)).await()
                    ops += Create(jumpRuleWithId(inRuleId, inChainId(rId),
                                                 inChainId(fw.getId)))
                    // Add the rule at the beginning of the pre-routing chain
                    // so that the firewall rules are evaluated before the NAT
                    // rules that may exist.
                    ops += Update(chain.toBuilder
                                      .addRuleIds(0, inRuleId).build())
                }

                val outRuleId = outChainFwJumpRuleId(rId)
                if (!storage.exists(classOf[Rule], outRuleId).await()) {
                    ops += Create(jumpRuleWithId(outRuleId, outChainId(rId),
                                                 outChainId(fw.getId)))
                }
            }
        }
        ops.toList
    }

    override protected def translateCreate(fw: NeutronFirewall) =
        firewallChains(fw.getId).map(Create(_)) ++
        preRoutingRules(fw).map(Create(_)) ++
        postRoutingRules(fw).map(Create(_)) ++
        translateRouterAssocs(fw)

    override protected def translateDelete(id: UUID) =
        firewallChains(id).map(c => Delete(classOf[Chain], c.getId))

    override protected def translateUpdate(fw: NeutronFirewall) = {
        val chains = storage.getAll(classOf[Chain],
                                    List(inChainId(fw.getId),
                                         outChainId(fw.getId))).await()
        translateRuleChainUpdate(fw, chains.head, preRoutingRules) ++
        translateRuleChainUpdate(fw, chains.last, postRoutingRules) ++
        translateRouterAssocs(fw)
    }
}

object FirewallTranslator {

    def preRouteChainName(id: UUID) = "OS_FW_PRE_ROUTING_" + id.asJava

    def postRouteChainName(id: UUID) = "OS_FW_POST_ROUTING_" + id.asJava

    def inChainFwJumpRuleId(routerId: UUID): UUID =
        routerId.xorWith(0xc309e08c3eaf11e5L, 0xaf410242ac110002L)

    def outChainFwJumpRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x1b4b62ca3eb011e5L, 0x91260242ac110002L)

    def toInt32Range(portRange: String): Int32Range = {
        // portRange could be a single value or two values delimited by ':'
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

    def toRule(r: NeutronFirewallRule, chainId: UUID): Rule = {
        // IPv6 not supported
        if (r.getIpVersion == IPVersion.V6_VALUE) {
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
            .setCondition(cond)
            .setAction(toTopologyRuleAction(r.getAction)).build()
    }
}