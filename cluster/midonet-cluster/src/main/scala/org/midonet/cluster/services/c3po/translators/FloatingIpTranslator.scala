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

import org.midonet.cluster.services.c3po.midonet.{Create, CreateNode, Delete, DeleteNode, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Route, Router, Rule}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.PathBuilder
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage,
                           protected val pathBldr: PathBuilder)
        extends NeutronTranslator[FloatingIp] with ChainManager
                                              with RouteManager
                                              with RuleManager
                                              with BridgeStateTableManager {
    import org.midonet.cluster.services.c3po.translators.RouteManager._
    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(fip: FloatingIp): MidoOpList = {
        // If a port is not assigned, there's nothing to do.
        if (!fip.hasPortId) return List()

        // Exit early if no gateway port is found.
        val router = storage.get(classOf[NeutronRouter], fip.getRouterId)
                            .await()
        if (!router.hasGwPortId) {
            throw new IllegalStateException(
                    "No gateway port was found to configure Floating IP.")
        }

        val midoOps = new MidoOpListBuffer

        midoOps += generateArpEntryCreateOp(fip, router.getGwPortId)
        midoOps ++= generateNatRuleCreateOps(fip, router.getGwPortId)

        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val midoOps = new MidoOpListBuffer

        val fip = storage.get(classOf[FloatingIp], id).await()
        if (fip.hasPortId) {
            val router = storage.get(classOf[NeutronRouter], fip.getRouterId)
                                .await()
            // Delete is idempotent
            midoOps += cleanUpArpEntry(fip, router.getGwPortId)
            midoOps ++= cleanUpNatRules(fip)
        }

        midoOps.toList
    }

    override protected def translateUpdate(fip: FloatingIp): MidoOpList = {
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()
        if ((!oldFip.hasPortId && !fip.hasPortId) ||
            (oldFip.hasPortId && fip.hasPortId &&
                    oldFip.getPortId == fip.getPortId &&
                    oldFip.getRouterId.equals(fip.getRouterId))) {
            // FIP was/is not assigned, or kept associated on the same router.
            // Do nothing.
            List()
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // FIP is un-associated from the port.
            val router = storage.get(classOf[NeutronRouter], oldFip.getRouterId)
                                .await()

            val midoOps = new MidoOpListBuffer
            midoOps += cleanUpArpEntry(oldFip, router.getGwPortId)
            midoOps ++= cleanUpNatRules(oldFip)
            midoOps.toList
        } else if (!oldFip.hasPortId && fip.hasPortId) {
            // FIP is newly associated.
            val router = storage.get(classOf[NeutronRouter], fip.getRouterId)
                                .await()
            if (!router.hasGwPortId) {
                throw new IllegalStateException(
                        "No gateway port was found to configure Floating IP.")
            }

            val midoOps = new MidoOpListBuffer
            midoOps += generateArpEntryCreateOp(fip, router.getGwPortId)
            midoOps ++= generateNatRuleCreateOps(fip, router.getGwPortId)
            midoOps.toList
        } else {
            // FIP is moved to a new port and/or a router.
            val oldRouterId = oldFip.getRouterId
            val newRouterId = fip.getRouterId
            // It shouldn't hurt if oldRouterId == newRouterId.
            val routers = storage.getAll(classOf[NeutronRouter],
                                         Seq(oldRouterId, newRouterId))
                                 .await()
            val oldRouter = routers(0)
            val newRouter = routers(1)

            val midoOps = new MidoOpListBuffer
            if (oldRouterId != newRouterId) {
                if (!newRouter.hasGwPortId)
                    throw new IllegalStateException(
                            "No gateway port was found with the Router.")

                midoOps += cleanUpArpEntry(fip, oldRouter.getGwPortId)
                midoOps += generateArpEntryCreateOp(fip, newRouter.getGwPortId)
            }

            // Clean up the old NAT rules and create new ones.
            midoOps ++= cleanUpNatRules(oldFip)
            midoOps ++= generateNatRuleCreateOps(fip, newRouter.getGwPortId)
            midoOps.toList
        }
    }

    /* Generates a CreateNode Op for FIP IP and Router GW port. */
    private def generateArpEntryCreateOp(fip: FloatingIp, gwPortId: UUID) = {
        val gwPort = storage.get(classOf[NeutronPort], gwPortId).await()
        CreateNode(arpEntryPath(gwPort.getNetworkId,
                                fip.getFloatingIpAddress.getAddress,
                                gwPort.getMacAddress))
    }

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def generateNatRuleCreateOps(fip: FloatingIp, gwPortId: UUID) = {
        val iChainId = inChainId(fip.getRouterId)
        val oChainId = outChainId(fip.getRouterId)
        val snatRule = Rule.newBuilder
            .setId(fipSnatRuleId(fip.getId))
            .setChainId(oChainId)
            .setAction(Rule.Action.ACCEPT)
            .addOutPortIds(gwPortId)
            .setNwSrcIp(IPSubnetUtil.fromAddr(fip.getFixedIpAddress))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = false))
            .build()
        val dnatRule = Rule.newBuilder
            .setId(fipDnatRuleId(fip.getId))
            .setChainId(iChainId)
            .setAction(Rule.Action.ACCEPT)
            .addInPortIds(gwPortId)
            .setNwDstIp(IPSubnetUtil.fromAddr(fip.getFloatingIpAddress))
            .setNatRuleData(natRuleData(fip.getFixedIpAddress, dnat = true))
            .build()

        val inChain = storage.get(classOf[Chain], iChainId).await()
        val outChain = storage.get(classOf[Chain], oChainId).await()
        val updatedInChain = prependRule(inChain, dnatRule.getId)
        val updatedOutChain = prependRule(outChain, snatRule.getId)

        List(Create(snatRule), Create(dnatRule),
             Update(updatedInChain), Update(updatedOutChain))
    }

    /* Since DeleteNode is idempotent, it is fine if the path does not exist. */
    private def cleanUpArpEntry(fip: FloatingIp, gwPortId: UUID) = {
        val gwPort = storage.get(classOf[NeutronPort], gwPortId).await()
        DeleteNode(arpEntryPath(gwPort.getNetworkId,
                                fip.getFloatingIpAddress.getAddress,
                                gwPort.getMacAddress))
    }

    /* Since Delete is idempotent, it is fine if those rules don't exist. */
    private def cleanUpNatRules(fip: FloatingIp): MidoOpList = {
        val fipId = fip.getId
        val routerId = fip.getRouterId
        val inChain = storage.get(classOf[Chain], inChainId(routerId)).await()
        val outChain = storage.get(classOf[Chain], outChainId(routerId)).await()

        List(Delete(classOf[Rule], fipSnatRuleId(fipId)),
             Delete(classOf[Rule], fipDnatRuleId(fipId)),
             Update(removeRule(inChain, fipDnatRuleId(fipId))),
             Update(removeRule(outChain, fipSnatRuleId(fipId))))
    }
}