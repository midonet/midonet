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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.{Condition, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Neutron.{NeutronFirewall, NeutronFirewallRule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Operation, Update}
import org.midonet.cluster.util.RangeUtil
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto}

class FirewallTranslator
    extends Translator[NeutronFirewall] with ChainManager with RuleManager
            with PortManager {
    import FirewallTranslator._

    private def firewallChain(fwId: UUID): Chain = {
        newChain(fwdChainId(fwId), fwdChainName(fwId))
    }

    private def fwdRules(fw: NeutronFirewall): List[Rule] = {
        val chainId = fwdChainId(fw.getId)
        val rules = new ListBuffer[Rule]

        // If admin state is down, add a DROP rule
        // NOTE: Translate rules even if the firewall is down
        // to avoid re-creating all rules when flipping admin state
        if (!fw.getAdminStateUp) {
            rules += dropRuleBuilder(chainId).build()
        }

        // Match on return
        // NOTE: This starts connection tracking
        rules += returnFlowRule(chainId)

        // Copy over the enabled rules
        rules ++= fw.getFirewallRuleListList.asScala
            .filter(_.getEnabled).map(r => toRule(r, chainId))

        // The default-drop rule
        rules += dropRuleBuilder(chainId).build()
        rules.toList
    }

    private def translateRuleChainUpdate(tx: Transaction,
                                         fw: NeutronFirewall,
                                         chain: Chain,
                                         generateFirewallChainRules:
                                             NeutronFirewall => List[Rule]): OperationList = {
        val oldRules = tx.getAll(classOf[Rule], chain.getRuleIdsList.asScala)
        val newRules = generateFirewallChainRules(fw)
        val (addedRules, updatedRules, removedIds) = getRuleChanges(
            oldRules.toList, newRules)

        removedIds.foreach(tx.delete(classOf[Rule], _, ignoresNeo = true))
        updatedRules.foreach(tx.update(_))
        addedRules.foreach(tx.create)

        // Set the order of the rules on this chain
        val cBuilder = chain.toBuilder.clearRuleIds()
        newRules.foreach(rule => cBuilder.addRuleIds(rule.getId))
        tx.update(cBuilder.build())
        List()
    }

    private def translateRouterAssocs(tx: Transaction,
                                      fw: NeutronFirewall): OperationList = {
        // Neutron guarantees that the router IDs in add-router-ids and
        // del-router-ids do not overlap, so there should never be a case
        // where both delete and create must be performed on the same router.
        // However, since deletion happens before creation, translation should
        // still succeed.

        // Detach the chain from the routers
        fw.getDelRouterIdsList.asScala foreach { rId =>
            deleteOldJumpRules(tx, rId)

            // Remove the FW jump rule from the router
            tx.delete(classOf[Rule], fwdChainFwJumpRuleId(rId),
                      ignoresNeo = true)
        }

        // Attach the chain to the routers
        fw.getAddRouterIdsList.asScala foreach { rId =>
            // Delete the jump rules to the deprecated fw chains so the new
            // jump rule to 'fwdChain' replaces them
            deleteOldJumpRules(tx, rId)

            val fwdRuleId = fwdChainFwJumpRuleId(rId)

            // Because Neutron sets the add-router-ids field to a set of
            // currently associated router IDs instead of newly associated,
            // make sure to check whether the association exists first.
            if (!tx.exists(classOf[Rule], fwdRuleId)) {
                val chain = ensureRouterFwdChain(tx, rId)
                val portGroupId = ensureRouterInterfacePortGroup(tx, rId)
                val cond = anyFragCondition
                    .setInPortGroupId(portGroupId)
                    .setInvInPortGroup(true)
                    .setOutPortGroupId(portGroupId)
                    .setInvOutPortGroup(true)
                    .setConjunctionInv(true).build
                tx.create(jumpRuleWithId(fwdRuleId, chain.getId,
                                         fwdChainId(fw.getId), cond))
                tx.update(chain.toBuilder.addRuleIds(fwdRuleId).build())
            }
        }

        List()
    }

    private def deleteOldJumpRules(tx: Transaction, rId: UUID): Unit = {
        // Remove old translations if any
        tx.delete(classOf[Rule], inChainFwJumpRuleId(rId), ignoresNeo = true)
        tx.delete(classOf[Rule], outChainFwJumpRuleId(rId), ignoresNeo = true)
    }

    private def ensureRouterFwdChain(tx: Transaction, rId: UUID): Chain = {
        val chainId = fwdChainId(rId)

        if (tx.exists(classOf[Chain], chainId)) {
            tx.get(classOf[Chain], chainId)
        } else {
            // A router with old translation might not have
            // the forward chain
            log.info(s"Creating fwd chain for ${fromProto(rId)}")
            val name = RouterTranslator.forwardChainName(rId)
            val chain = newChain(chainId, name)
            val router = tx.get(classOf[Router], rId)
            val bldr = router.toBuilder.setForwardChainId(chainId)
            tx.create(chain)
            tx.update(bldr.build)
            chain
        }
    }

    override protected def translateCreate(tx: Transaction,
                                           fw: NeutronFirewall): Unit = {
        tx.create(firewallChain(fw.getId))
        fwdRules(fw).foreach(tx.create(_))
        translateRouterAssocs(tx, fw)
    }

    private def translateFwJumpRuleDel(tx: Transaction,
                                       fw: NeutronFirewall): Unit = {
        // Delete the firewall jump rules manually since ZOOM currently does
        // not delete them automatically.
        fw.getAddRouterIdsList.asScala.foreach { routerId =>
            deleteOldJumpRules(tx, routerId)
            tx.delete(classOf[Rule], fwdChainFwJumpRuleId(routerId),
                      ignoresNeo = true)
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           nfw: NeutronFirewall): Unit = {
        translateFwJumpRuleDel(tx, nfw)
        tx.delete(classOf[Chain], firewallChain(nfw.getId).getId,
                  ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           fw: NeutronFirewall): Unit = {
        // Note: We need to update the high level model by ourselves
        // because it might not be an Update.
        if (fw.hasLastRouter && fw.getLastRouter) {
            // A firewall with no routers associated, going to INACTIVE.
            // Just forget it completely.
            val fwId = fw.getId
            log.debug(s"Deleting firewall ${fromProto(fwId)} because " +
                      "no routers are associated")
            translateDelete(tx, fwId)
            tx.delete(classOf[NeutronFirewall], fwId, ignoresNeo = true)
        } else {
            val chainId = fwdChainId(fw.getId)

            if (tx.exists(classOf[Chain], chainId)) {
                val chain = tx.get(classOf[Chain], chainId)

                // Update a firewall known to us.
                translateRuleChainUpdate(tx, fw, chain, fwdRules)
                tx.update(fw)
                translateRouterAssocs(tx, fw)
            } else {
                // Update a firewall not known to us.  Just create one.
                // This case happens when the neutron plugin omits
                // the Create RPC because of the empty router list.
                log.debug(s"Creating firewall ${fromProto(fw.getId)} " +
                          "for Update request")
                tx.create(fw)
                translateCreate(tx, fw)
            }
        }
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronFirewall])
    : List[Operation[NeutronFirewall]] = {
        op match {
            case Update(_, _) => List()  // See translateUpdate
            case _ => super.retainHighLevelModel(tx, op)
        }
    }
}

object FirewallTranslator {

    /* old translation */
    def inChainFwJumpRuleId(routerId: UUID): UUID =
        routerId.xorWith(0xc309e08c3eaf11e5L, 0xaf410242ac110002L)

    /* old translation */
    def outChainFwJumpRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x1b4b62ca3eb011e5L, 0x91260242ac110002L)

    def fwdChainName(id: UUID) = "OS_FW_FORWARD_" + id.asJava

    def fwdChainFwJumpRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x36feb6f6fd9211e5L, 0x98a00242ac110001L)

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

        val condition = Condition.newBuilder()
        if (r.hasProtocol) {
            condition.setNwProto(r.getProtocol.getNumber)
        }

        if (r.hasSourceIpAddress) {
            condition.setNwSrcIp(r.getSourceIpAddress)
        }

        if (r.hasDestinationIpAddress) {
            condition.setNwDstIp(r.getDestinationIpAddress)
        }

        if (r.hasSourcePort) {
            condition.setTpSrc(RangeUtil.strToInt32Range(r.getSourcePort))
        }

        if (r.hasDestinationPort) {
            condition.setTpDst(RangeUtil.strToInt32Range(r.getDestinationPort))
        }

        Rule.newBuilder()
            .setId(r.getId)
            .setChainId(chainId)
            .setCondition(condition)
            .setAction(toTopologyRuleAction(r.getAction)).build()
    }
}
