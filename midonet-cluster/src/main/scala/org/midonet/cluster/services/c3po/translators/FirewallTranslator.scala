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

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.{Condition, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Neutron.{NeutronFirewall, NeutronFirewallRule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update, Operation}
import org.midonet.cluster.util.RangeUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

class FirewallTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[NeutronFirewall]
            with ChainManager with RuleManager {
    import FirewallTranslator._

    private def firewallChains(fwId: UUID) =
        List(newChain(fwdChainId(fwId), fwdChainName(fwId)))

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
        // NOTE: This starts conneciton tracking
        rules += returnFlowRule(chainId)

        // Copy over the enabled rules
        rules ++= fw.getFirewallRuleListList.asScala
            .filter(_.getEnabled).map(r => toRule(r, chainId))

        // The default-drop rule
        rules += dropRuleBuilder(chainId).build()
        rules.toList
    }

    private def translateRuleChainUpdate(fw: NeutronFirewall,
                                         chain: Chain,
                                         generateFirewallChainRules:
                                             NeutronFirewall => List[Rule]): OperationList = {
        val ops = new OperationListBuffer
        val oldRules = storage.getAll(classOf[Rule],
                                      chain.getRuleIdsList.asScala).await()
        val newRules = generateFirewallChainRules(fw)
        val (addedRules, updatedRules, removedIds) = getRuleChanges(
            oldRules.toList, newRules)

        ops ++= removedIds.map(Delete(classOf[Rule], _))
        ops ++= updatedRules.map(Update(_))
        ops ++= addedRules.map(Create(_))

        // Set the order of the rules on this chain
        val cBuilder = chain.toBuilder.clearRuleIds()
        newRules.foreach(r => cBuilder.addRuleIds(r.getId))
        ops += Update(cBuilder.build())
        ops.toList
    }

    private def translateRouterAssocs(fw: NeutronFirewall): OperationList = {
        val ops = new OperationListBuffer

        // Neutron guarantees that the router IDs in add-router-ids and
        // del-router-ids do not overlap, so there should never be a case
        // where both delete and create must be performed on the same router.
        // However, since deletion happens before creation, translation should
        // still succeed.

        // Detach the chain from the routers
        fw.getDelRouterIdsList.asScala foreach { rId =>
            ops ++= deleteOldChains(rId)

            // Remove the FW jump rule from the router
            ops += Delete(classOf[Rule], fwdChainFwJumpRuleId(rId))
        }

        // Attach the chain to the routers
        fw.getAddRouterIdsList.asScala foreach { rId =>
            ops ++= deleteOldChains(rId)

            val fwdRuleId = fwdChainFwJumpRuleId(rId)

            // Because Neutron sets the add-router-ids field to a set of
            // currently associated router IDs instead of newly associated,
            // make sure to check whether the association exists first.
            if (!storage.exists(classOf[Rule], fwdRuleId).await()) {
                val (routerOps, chain) = ensureRouterFwdChain(rId)
                ops ++= routerOps
                ops += Create(jumpRuleWithId(fwdRuleId, chain.getId,
                                             fwdChainId(fw.getId)))
                ops += Update(chain.toBuilder.addRuleIds(fwdRuleId).build())
            }
        }

        ops.toList
    }

    private def deleteOldChains(rId: UUID): OperationList = {
        // Remove old translations if any
        List(Delete(classOf[Rule], inChainFwJumpRuleId(rId)),
            Delete(classOf[Rule], outChainFwJumpRuleId(rId)))
    }

    private def ensureRouterFwdChain(rId: UUID): (OperationList, Chain) = {
        val chainId = fwdChainId(rId)
        try {
            val chain = storage.get(classOf[Chain], chainId).await()
            (List(), chain)
        } catch {
            case nfe: NotFoundException =>
                // A router with old translation might not have
                // the forward chain
                log.info("Creating fwd chain for {}", rId)
                val name = RouterTranslator.forwardChainName(rId)
                val chain = newChain(chainId, name)
                val router = storage.get(classOf[Router], rId).await()
                val bldr = router.toBuilder.setForwardChainId(chainId)
                (List(Create(chain), Update(bldr.build)), chain)
        }
    }

    override protected def translateCreate(fw: NeutronFirewall) =
        firewallChains(fw.getId).map(Create(_)) ++
        fwdRules(fw).map(Create(_)) ++
        translateRouterAssocs(fw)

    private def translateFwJumpRuleDel(fwId: UUID): OperationList = {
        // Delete the firewall jump rules manually since ZOOM currently does
        // not delete them automatically.
        val fw = storage.get(classOf[NeutronFirewall], fwId).await()
        fw.getAddRouterIdsList.asScala.flatMap(
            rId => List(Delete(classOf[Rule], inChainFwJumpRuleId(rId)),
                        Delete(classOf[Rule], outChainFwJumpRuleId(rId)))).toList
    }

    override protected def translateDelete(id: UUID) =
        translateFwJumpRuleDel(id) ++
        firewallChains(id).map(c => Delete(classOf[Chain], c.getId))

    override protected def translateUpdate(fw: NeutronFirewall) = {
        // Note: We need to update the high level model by ourselves
        // because it might not be an Update.
        if (fw.hasLastRouter && fw.getLastRouter) {
            // A firewall with no routers associated, going to INACTIVE.
            // Just forget it completely.
            val fwId = fw.getId
            log.debug("Deleting firewall {} because no routers are associated",
                      fwId)
            List(Delete(classOf[NeutronFirewall], fwId)) ++ translateDelete(fwId)
        } else {
            val chainId = fwdChainId(fw.getId)
            val chain = try {
                storage.get(classOf[Chain], chainId).await()
            } catch {
                case nfe: NotFoundException => null
            }
            if (chain ne null) {
                // Update a firewall known to us.
                translateRuleChainUpdate(fw, chain, fwdRules) ++
                List(Update(fw)) ++ translateRouterAssocs(fw)
            } else {
                // Update a firewall not known to us.  Just create one.
                // This case happens when the neutron plugin omits
                // the Create RPC because of the empty router list.
                log.debug("Creating firewall {} for Update request", fw.getId)
                List(Create(fw)) ++ translateCreate(fw)
            }
        }
    }

    override protected def retainHighLevelModel(op: Operation[NeutronFirewall])
    : List[Operation[NeutronFirewall]] = {
        op match {
            case Update(nm, _) => List()  // See translateUpdate
            case _ => super.retainHighLevelModel(op)
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
        routerId.xorWith(0x1b4b62ca3eb011e5L, 0x91260242ac110002L)

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
            cond.setTpSrc(RangeUtil.strToInt32Range(r.getSourcePort))
        }

        if (r.hasDestinationPort) {
            cond.setTpDst(RangeUtil.strToInt32Range(r.getDestinationPort))
        }

        Rule.newBuilder()
            .setId(r.getId)
            .setChainId(chainId)
            .setCondition(cond)
            .setAction(toTopologyRuleAction(r.getAction)).build()
    }
}
