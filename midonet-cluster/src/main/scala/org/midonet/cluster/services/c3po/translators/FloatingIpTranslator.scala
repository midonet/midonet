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
import org.midonet.cluster.models.Commons.{Condition, UUID}
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Rule}
import org.midonet.cluster.services.c3po.midonet.{Create, CreateNode, Delete, DeleteNode, Update}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.PathBuilder
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage,
                           protected val pathBldr: PathBuilder)
        extends Translator[FloatingIp] with ChainManager
                with RouteManager
                with RuleManager
                with BridgeStateTableManager {
    import RouterTranslator.tenantGwPortId
    import org.midonet.cluster.services.c3po.translators.RouteManager._

    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(fip: FloatingIp): MidoOpList = {
        // If a port is not assigned, there's nothing to do.
        if (!fip.hasPortId) List() else associateFipOps(fip)
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val fip = storage.get(classOf[FloatingIp], id).await()
        if (!fip.hasPortId) List() else disassociateFipOps(fip)
    }

    override protected def translateUpdate(fip: FloatingIp): MidoOpList = {
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()
        if ((!oldFip.hasPortId && !fip.hasPortId) ||
            (oldFip.hasPortId && fip.hasPortId &&
                    oldFip.getPortId == fip.getPortId &&
                    oldFip.getRouterId == fip.getRouterId)) {
            // FIP's portId and routerId are both unchanged. Do nothing.
            List()
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // FIP is un-associated from the port.
            disassociateFipOps(oldFip)
        } else if (!oldFip.hasPortId && fip.hasPortId) {
            // FIP is newly associated.
            associateFipOps(fip)
        } else {
            val newGwPortId = getRouterGwPortId(fip.getRouterId)
            val midoOps = new MidoOpListBuffer
            if (oldFip.getRouterId != fip.getRouterId) {
                val oldGwPortId = getRouterGwPortId(oldFip.getRouterId)
                midoOps += removeArpEntry(fip, oldGwPortId)
                midoOps += addArpEntry(fip, newGwPortId)
            }

            midoOps ++= removeNatRules(oldFip)
            midoOps ++= addNatRules(fip, newGwPortId)
            midoOps.toList
        }
    }

    private def fipArpEntryPath(fip: FloatingIp, gwPortId: UUID) = {
        val gwPort = storage.get(classOf[NeutronPort], gwPortId).await()
        arpEntryPath(gwPort.getNetworkId,
                     fip.getFloatingIpAddress.getAddress,
                     gwPort.getMacAddress)
    }

    /* Generates a CreateNode Op for FIP IP and Router GW port. */
    private def addArpEntry(fip: FloatingIp, gwPortId: UUID) =
        CreateNode(fipArpEntryPath(fip, gwPortId))

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def addNatRules(fip: FloatingIp, gwPortId: UUID): MidoOpList = {
        val iChainId = inChainId(fip.getRouterId)
        val oChainId = outChainId(fip.getRouterId)
        val routerGwPortId = tenantGwPortId(gwPortId)
        val snatRule = Rule.newBuilder
            .setId(fipSnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(Condition.newBuilder
                              .addOutPortIds(routerGwPortId)
                              .setNwSrcIp(IPSubnetUtil.fromAddr(
                                              fip.getFixedIpAddress)))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = false,
                                        dynamic = false))
            .build()
        val dnatRule = Rule.newBuilder
            .setId(fipDnatRuleId(fip.getId))
            .setType(Rule.Type.NAT_RULE)
            .setAction(Rule.Action.ACCEPT)
            .setFipPortId(fip.getPortId)
            .setCondition(Condition.newBuilder
                              .addInPortIds(routerGwPortId)
                              .setNwDstIp(IPSubnetUtil.fromAddr(
                                              fip.getFloatingIpAddress)))
            .setNatRuleData(natRuleData(fip.getFixedIpAddress, dnat = true,
                                        dynamic = false))
            .build()

        val inChain = storage.get(classOf[Chain], iChainId).await()
        val outChain = storage.get(classOf[Chain], oChainId).await()

        val ops = new MidoOpListBuffer
        ops += Create(snatRule)
        ops += Create(dnatRule)

        // When an update changes the FIP's association from one port to another
        // behind the same router, the DNAT and SNAT rules' IDs will already be
        // in the chains' rule ID lists, because the changes that disassociated
        // the FIP from the old port are part of the same Neutron operation
        // (see removeNatRules()) and haven't been persisted yet. We need to
        // avoid duplicate entries in the chains' rule ID lists.
        //
        // We do still need to update the chains even though we're not actually
        // changing them, because the rule deletion operations created in
        // removeNatRules() will clear those rules' IDs from the chains' rule
        // ID lists due to the Zoom bindings between Rule.chain_id and
        // Chain.rule_ids.
        if (!inChain.getRuleIdsList.contains(dnatRule.getId))
            ops += Update(prependRule(inChain, dnatRule.getId))
        else ops += Update(inChain)

        if (!outChain.getRuleIdsList.contains(snatRule.getId))
            ops += Update(prependRule(outChain, snatRule.getId))
        else ops += Update(outChain)

        ops.toList
    }

    private def getRouterGwPortId(routerId: UUID): UUID = {
        val router = storage.get(classOf[NeutronRouter], routerId).await()
        if (!router.hasGwPortId)
            throw new IllegalStateException(
                "No gateway port was found to configure Floating IP.")
        router.getGwPortId
    }

    private def associateFipOps(fip: FloatingIp): MidoOpList = {
        val rGwPortId = getRouterGwPortId(fip.getRouterId)
        addArpEntry(fip, rGwPortId) +: addNatRules(fip, rGwPortId)
    }

    private def disassociateFipOps(fip: FloatingIp): MidoOpList = {
        val rGwPortId = getRouterGwPortId(fip.getRouterId)
        removeArpEntry(fip, rGwPortId) +: removeNatRules(fip)
    }

    /* Since DeleteNode is idempotent, it is fine if the path does not exist. */
    private def removeArpEntry(fip: FloatingIp, gwPortId: UUID) =
        DeleteNode(fipArpEntryPath(fip, gwPortId))

    /* Since Delete is idempotent, it is fine if those rules don't exist. */
    private def removeNatRules(fip: FloatingIp): MidoOpList = {
        List(Delete(classOf[Rule], fipSnatRuleId(fip.getId)),
             Delete(classOf[Rule], fipDnatRuleId(fip.getId)))
    }
}
