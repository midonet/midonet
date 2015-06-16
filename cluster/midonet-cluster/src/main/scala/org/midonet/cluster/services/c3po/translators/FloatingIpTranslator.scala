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

import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Route, Router, Rule}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage)
        extends NeutronTranslator[FloatingIp] with RouteManager
                                              with RuleManager {
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

        midoOps += generateGwRouteCreateOp(fip, router.getGwPortId)
        midoOps ++= generateNatRuleCreateOps(fip, router.getGwPortId)

        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val midoOps = new MidoOpListBuffer

        val fip = storage.get(classOf[FloatingIp], id).await()
        if (fip.hasPortId) {
            // Delete is idempotent
            midoOps ++= cleanUpGatewayRoutesAndNatRules(fip)
        }

        midoOps.toList
    }

    override protected def translateUpdate(fip: FloatingIp): MidoOpList = {
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()
        if ((!oldFip.hasPortId && !fip.hasPortId) ||
            (oldFip.hasPortId && fip.hasPortId &&
                    oldFip.getRouterId.equals(fip.getRouterId))) {
            // FIP was/is not assigned, or kept associated on the same router.
            // Do nothing.
            List()
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // FIP is un-associated from the port.
            cleanUpGatewayRoutesAndNatRules(fip)
        } else {
            // FIP is newly associated or moved to a new router.
            val midoOps = new MidoOpListBuffer

            val routerId = fip.getRouterId
            val router = storage.get(classOf[NeutronRouter], routerId).await()
            if (!router.hasGwPortId) {
                throw new IllegalStateException(
                        "No gateway port was found to configure Floating IP.")
            }

            if (oldFip.hasPortId && !oldFip.getRouterId.equals(routerId))
                // Delete old NAT rules as FIP's been moved to a new router.
                midoOps ++= cleanUpNatRules(oldFip)
            else if (!oldFip.hasPortId) // FIP is newly associated.
                // Generate a gateway route.
                midoOps += generateGwRouteCreateOp(fip, router.getGwPortId)

            midoOps ++= generateNatRuleCreateOps(fip, router.getGwPortId)
            midoOps.toList
        }
    }

    /* Generates a Create Op for the GW route. */
    private def generateGwRouteCreateOp(fip: FloatingIp, gwPortId: UUID) =
        Create(newNextHopPortRoute(gwPortId,
                                   id = fipGatewayRouteId(fip.getId),
                                   dstSubnet = IPSubnetUtil.fromAddr(
                                           fip.getFloatingIpAddress)))

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def generateNatRuleCreateOps(fip: FloatingIp, gwPortId: UUID) = {
        val iChainId = ChainManager.inChainId(fip.getRouterId)
        val oChainId = ChainManager.outChainId(fip.getRouterId)
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
        val updatedInChain = ChainManager.prependRule(inChain, dnatRule.getId)
        val updatedOutChain = ChainManager.prependRule(outChain,
                                                       snatRule.getId)

        List(Create(snatRule), Create(dnatRule),
             Update(updatedInChain), Update(updatedOutChain))
    }

    /* Delete the gateway routes and SNAT / DNAT rules associated with FIP. */
    private def cleanUpGatewayRoutesAndNatRules(fip: FloatingIp): MidoOpList = {
        cleanUpGatewayRoutes(fip) ++ cleanUpNatRules(fip)
    }

    /* Since Delete is idempotent, it is fine if the route doesn't exist. */
    private def cleanUpGatewayRoutes(fip: FloatingIp): MidoOpList =
        List(Delete(classOf[Route], fipGatewayRouteId(fip.getId)))

    /* Since Delete is idempotent, it is fine if those rules don't exist. */
    private def cleanUpNatRules(fip: FloatingIp): MidoOpList = {
        val fipId = fip.getId
        val routerId = fip.getRouterId
        val inChain = storage.get(classOf[Chain],
                                  ChainManager.inChainId(routerId)).await()
        val outChain = storage.get(classOf[Chain],
                                   ChainManager.outChainId(routerId)).await()

        List(Delete(classOf[Rule], fipSnatRuleId(fipId)),
             Delete(classOf[Rule], fipDnatRuleId(fipId)),
             Update(ChainManager.removeRule(inChain, fipDnatRuleId(fipId))),
             Update(ChainManager.removeRule(outChain, fipSnatRuleId(fipId))))
    }
}