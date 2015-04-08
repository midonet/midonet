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

package org.midonet.brain.services.c3po.translators

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Route, Router, Rule}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage)
        extends NeutronTranslator[FloatingIp] with ChainManager
                                              with RouteManager
                                              with RuleManager {
    import org.midonet.brain.services.c3po.translators.RouteManager._
    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(fip: FloatingIp): MidoOpList = {
        // If a port is not assigned, there's nothing to do.
        if (!fip.hasPortId) return List()

        // Exit early if no gateway port is found.
        val router = storage.get(classOf[NeutronRouter], fip.getRouterId)
                            .await()
        if (!router.hasGwPortId) {
            log.warn("No gateway port was found to configure Floating IP.")
            return List()
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
        val midoOps = new MidoOpListBuffer
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()
        if (!oldFip.hasPortId && fip.hasPortId) {
            // Exit early if no gateway port is found.
            val router = storage.get(classOf[NeutronRouter], fip.getRouterId)
                                .await()
            if (!router.hasGwPortId) {
                log.warn("No gateway port was found to configure Floating IP.")
                return List()
            }

            midoOps += generateGwRouteCreateOp(fip, router.getGwPortId)
            midoOps ++= generateNatRuleCreateOps(fip, router.getGwPortId)
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            // Delete is idempotent
            midoOps ++= cleanUpGatewayRoutesAndNatRules(fip)
        }
        // If the Floating IP is kept associated, no need to update the route.

        midoOps.toList
    }

    /* Generates a Create Op for the GW route. */
    private def generateGwRouteCreateOp(fip: FloatingIp, gwPortId: UUID) =
        Create(newNextHopPortRoute(gwPortId,
                                   id = fipGatewayRouteId(fip.getId),
                                   dstSubnet = IPSubnetUtil.fromAddr(
                                           fip.getFloatingIpAddress)))

    /* Generate Create Ops for SNAT and DNAT for the floating IP address. */
    private def generateNatRuleCreateOps(fip: FloatingIp, gwPortId: UUID) = {
        val mRouter = storage.get(classOf[Router], fip.getRouterId).await()
        val snatRule = Rule.newBuilder
            .setId(fipSnatRuleId(fip.getId))
            .setChainId(mRouter.getOutboundFilterId)
            .setAction(Rule.Action.ACCEPT)
            .addOutPortIds(gwPortId)
            .setNwSrcIp(IPSubnetUtil.fromAddr(fip.getFixedIpAddress))
            .setNatRuleData(natRuleData(fip.getFloatingIpAddress, dnat = false))
            .build()
        val dnatRule = Rule.newBuilder
            .setId(fipDnatRuleId(fip.getId))
            .setChainId(mRouter.getInboundFilterId)
            .setAction(Rule.Action.ACCEPT)
            .addInPortIds(gwPortId)
            .setNwDstIp(IPSubnetUtil.fromAddr(fip.getFloatingIpAddress))
            .setNatRuleData(natRuleData(fip.getFixedIpAddress, dnat = true))
            .build()

        val inChain = storage.get(classOf[Chain], mRouter.getInboundFilterId)
                             .await()
        val outChain = storage.get(classOf[Chain], mRouter.getOutboundFilterId)
                              .await()
        val updatedInChain = inChain.toBuilder
                                    .addRuleIds(0, dnatRule.getId).build()
        val updatedOutChain = outChain.toBuilder
                                      .addRuleIds(0, snatRule.getId).build()

        List(Create(snatRule), Create(dnatRule),
             Update(updatedInChain), Update(updatedOutChain))
    }

    /* Delete the gateway routes and SNAT / DNAT rules. Since Delete is
     * idempotent, it is fine if those routes / rules don't exist. */
    private def cleanUpGatewayRoutesAndNatRules(fip: FloatingIp): MidoOpList = {
        val fipId = fip.getId
        val inChain = storage.get(classOf[Chain],
                                  inChainId(fip.getRouterId)).await()
        val outChain = storage.get(classOf[Chain],
                                   outChainId(fip.getRouterId)).await()

        List(Delete(classOf[Route], fipGatewayRouteId(fipId)),
             Delete(classOf[Rule], fipSnatRuleId(fipId)),
             Delete(classOf[Rule], fipDnatRuleId(fipId)),
             Update(removeRule(inChain, fipDnatRuleId(fipId))),
             Update(removeRule(outChain, fipSnatRuleId(fipId))))
    }
}