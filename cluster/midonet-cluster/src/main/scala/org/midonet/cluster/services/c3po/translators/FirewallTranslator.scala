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
import org.midonet.cluster.models.Commons.{Int32Range, UUID}
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Neutron.{NeutronFirewall, NeutronFirewallRule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

import scala.collection.JavaConverters._

class FirewallTranslator(protected val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronFirewall]
            with ChainManager with RuleManager {
    import FirewallTranslator._

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

    override protected def translateCreate(fw: NeutronFirewall): MidoOpList = {

        // Create chains for the firewall
        val inFwChain = newChain(inChainId(fw.getId),
                                 preRouteChainName(fw.getId))
        val outFwChain = newChain(outChainId(fw.getId),
                                  postRouteChainName(fw.getId))

        val ops = new MidoOpListBuffer
        ops += Create(inFwChain)
        ops += Create(outFwChain)

        // Pre-routing chain

        // Match on return
        ops += Create(returnFlowRule(inFwChain.getId))

        // Copy over the rules
        ops ++ fw.getFirewallRuleListList.asScala.map(
            r => Create(toPreRoutingRule(r, inFwChain.getId)))

        // Drop rule at the end of inbound
        ops += Create(dropRuleBuilder(inFwChain.getId).build())

        // Post routing chain

        // match on forward flow
        ops += Create(forwardFlowRule(outFwChain.getId))

        // Attach the chains to the routers
        ops ++ translateRouterAssocs(fw)

        ops.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {

        // Deleting the FW chains should automatically detach them from the
        // routers and delete the rules.
        // TODO(Ryu): Find out whether it also deletes the jump rules
        List(Delete(classOf[Chain], inChainId(id)),
             Delete(classOf[Chain], outChainId(id)))
    }

    override protected def translateUpdate(fw: NeutronFirewall): MidoOpList = {

        val ops = new MidoOpListBuffer

        // Update is the most complex operation.  The following things need to
        // be handled:
        //     1. Rules deleted/added
        //     2. Routers associated/disassociated

        val inFwChain = storage.get(classOf[Chain],
                                    inChainId(fw.getId)).await()
        val oldRules = storage.getAll(classOf[Rule],
                                      inFwChain.getRuleIdsList.asScala).await()
        val newRules = fw.getFirewallRuleListList.asScala.map(
            r => toPreRoutingRule(r, inFwChain.getId))

        val (addedRules, updatedRules, removedIds) = getRuleChanges(
                oldRules.toList, newRules.toList)

        // TODO(RYU): how about the order?
        removedIds.map(Delete(classOf[Rule], _)) ++
        updatedRules.map(Update(_)) ++
        addedRules.map(Create(_)) ++
        translateRouterAssocs(fw)
    }
}

object FirewallTranslator {

    def preRouteChainName(id: UUID) = "OS_FW_PRE_ROUTING_" + id.asJava

    def postRouteChainName(id: UUID) = "OS_FW_POST_ROUTING_" + id.asJava

    def inChainFwJumpRule(routerId: UUID): UUID =
        routerId.xorWith(0xc309e08c3eaf11e5L, 0xaf410242ac110002L)

    def outChainFwJumpRule(routerId: UUID): UUID =
        routerId.xorWith(0x1b4b62ca3eb011e5L, 0x91260242ac110002L)

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

        val rule = Rule.newBuilder().setId(r.getId).setChainId(chainId)

        if (r.hasProtocol) {
            rule.setNwProto(r.getProtocol.getNumber)
        }

        if (r.hasSourceIpAddress) {
            rule.setNwSrcIp(r.getSourceIpAddress)
        }

        if (r.hasDestinationIpAddress) {
            rule.setNwDstIp(r.getDestinationIpAddress)
        }

        if (r.hasSourcePort) {
            rule.setTpSrc(toInt32Range(r.getSourcePort))
        }

        if (r.hasDestinationPort) {
            rule.setTpDst(toInt32Range(r.getDestinationPort))
        }

        rule.setAction(
            toTopologyRuleAction(r.getAction)).build()
    }
}