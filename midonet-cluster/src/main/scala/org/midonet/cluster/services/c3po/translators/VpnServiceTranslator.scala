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

import java.util.{UUID => JUUID}

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, VpnService}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Operation, Update}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, SequenceDispenser}
import org.midonet.containers
import org.midonet.packets.MAC

class VpnServiceTranslator(sequenceDispenser: SequenceDispenser)
    extends Translator[VpnService] with ChainManager with PortManager
            with RouteManager with RuleManager {
    import VpnServiceTranslator._

    override protected def translateCreate(tx: Transaction,
                                           vpn: VpnService): OperationList = {
        val routerId = vpn.getRouterId
        val router = tx.get(classOf[Router], routerId)

        // Nothing to do if the router already has a VPNService.
        // We only support one container per router now
        val existing = tx.getAll(classOf[VpnService], router.getVpnServiceIdsList)
        existing.headOption match {
            case Some(existingVpnService) =>
                tx.create(vpn.toBuilder
                              .setContainerId(existingVpnService.getContainerId)
                              .setExternalIp(existingVpnService.getExternalIp)
                              .build())
                return List()
            case None =>
        }

        val neutronRouter = tx.get(classOf[NeutronRouter], routerId)
        if (!neutronRouter.hasGwPortId) {
            throw new IllegalArgumentException(
                "Cannot discover gateway port of router")
        }
        val neutronGatewayPort = tx.get(classOf[NeutronPort],
                                             neutronRouter.getGwPortId)
        val externalIp = if (neutronGatewayPort.getFixedIpsCount > 0) {
            neutronGatewayPort.getFixedIps(0).getIpAddress
        } else {
            throw new IllegalArgumentException(
                "Cannot discover gateway IP of router")
        }

        if (externalIp.getVersion != IPVersion.V4) {
            throw new IllegalArgumentException(
                "Only IPv4 endpoints are currently supported")
        }

        val containerId = JUUID.randomUUID
        val routerPort = createRouterPort(tx, router)

        val vpnRoute = newNextHopPortRoute(id = vpnContainerRouteId(containerId),
                                           nextHopPortId = routerPort.getId,
                                           dstSubnet = routerPort.getPortSubnet)
        val localRoute = newLocalRoute(routerPort.getId,
                                       routerPort.getPortAddress)

        val chainId = if (router.hasLocalRedirectChainId) {
            router.getLocalRedirectChainId
        } else {
            val id = JUUID.randomUUID
            val chain = newChain(id, "LOCAL_REDIRECT_" + routerId.asJava)
            tx.create(chain)

            val routerWithChain = router.toBuilder
                .setLocalRedirectChainId(id)
                .build()
            tx.update(routerWithChain)

            id.asProto
        }

        val redirectRules = makeRedirectRules(
            chainId, externalIp, routerPort.getId)

        // TODO: Set port group ID (MI-300).
        val scg = ServiceContainerGroup.newBuilder
            .setId(JUUID.randomUUID)
            .setPolicy(ServiceContainerPolicy.LEAST_SCHEDULER)
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(containerId)
            .setServiceGroupId(scg.getId)
            .setPortId(routerPort.getId)
            .setServiceType(VpnServiceType)
            .setConfigurationId(routerId)
            .build()


        val modifiedVpnService = vpn.toBuilder
            .setContainerId(sc.getId)
            .setExternalIp(externalIp)
            .build()

        for (redirectRule <- redirectRules) {
            tx.create(redirectRule)
        }

        tx.create(routerPort)
        tx.create(vpnRoute)
        tx.create(localRoute)
        tx.create(scg)
        tx.create(sc)
        tx.create(modifiedVpnService)
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           vpn: VpnService): OperationList = {
        val router = tx.get(classOf[Router], vpn.getRouterId)

        val otherServices = tx.getAll(classOf[VpnService],
                                      router.getVpnServiceIdsList)
            .count((other: VpnService) => {
                       vpn.getContainerId == other.getContainerId &&
                       vpn.getId != other.getId
                   })
        if (otherServices == 0) {
            if (router.hasLocalRedirectChainId) {
                val chainId = router.getLocalRedirectChainId
                val chain = tx.get(classOf[Chain], chainId)
                val rules = tx.getAll(classOf[Rule], chain.getRuleIdsList())
                val rulesToDelete = filterVpnRedirectRules(vpn.getExternalIp, rules)
                    .map(_.getId)
                if (rulesToDelete.size == chain.getRuleIdsCount) {
                    tx.update(router.toBuilder
                                  .clearLocalRedirectChainId()
                                  .clearVpnServiceIds()
                                  .build())
                    tx.delete(classOf[Chain], chainId, ignoresNeo = true)
                } else { // just delete the rules
                    for (ruleId <- rulesToDelete) {
                        tx.delete(classOf[Rule], ruleId, ignoresNeo = true)
                    }
                }
            }

            val container = tx.get(classOf[ServiceContainer], vpn.getContainerId)

            tx.delete(classOf[Port], container.getPortId, ignoresNeo = true)
            tx.delete(classOf[ServiceContainerGroup], container.getServiceGroupId,
                      ignoresNeo = true)
        }
        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           vpn: VpnService): OperationList = {
        // No Midonet-specific changes, but changes to the VPNService are
        // handled in retainHighLevelModel().
        List()
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[VpnService])
    : List[Operation[VpnService]] = op match {
        case Create(_) =>
            // Do nothing, the translator creates the VPN object.
            List()
        case Update(vpn, _) =>
            // Need to override update to make sure only certain fields are
            // updated, to avoid overwriting ipsec_site_conn_ids, which Neutron
            // doesn't know about.
            val oldVpn = tx.get(classOf[VpnService], vpn.getId)
            val newVpn = vpn.toBuilder
                .addAllIpsecSiteConnectionIds(oldVpn.getIpsecSiteConnectionIdsList)
                .setContainerId(oldVpn.getContainerId)
                .setExternalIp(oldVpn.getExternalIp)
                .build()
            List(Update(newVpn))
        case _ => super.retainHighLevelModel(tx, op)
    }

    @throws[NoSuchElementException]
    private def createRouterPort(tx: Transaction, router: Router): Port = {
        val currentPorts = tx.getAll(classOf[Port], router.getPortIdsList)

        val subnet = containers.findLocalSubnet(currentPorts)
        val routerAddr = containers.routerPortAddress(subnet)

        val portId = JUUID.randomUUID
        val interfaceName = s"vpn-${portId.toString.substring(0, 8)}"
        val builder = Port.newBuilder
            .setId(portId)
            .setRouterId(router.getId)
            .setPortSubnet(subnet)
            .setPortAddress(routerAddr)
            .setPortMac(MAC.random().toString)
            .setInterfaceName(interfaceName)
        assignTunnelKey(builder, sequenceDispenser)
        builder.build()
    }

    private def makeRedirectRules(chainId: UUID,
                                  externalIp: IPAddress,
                                  portId: UUID): List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddr(externalIp)

        // Redirect ESP traffic addressed to local endpoint to VPN port.
        val espRuleBldr = redirectRuleBuilder(
            id = Some(JUUID.randomUUID),
            chainId = chainId,
            targetPortId = portId)
        espRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(50) // ESP

        // Redirect UDP traffic addressed to local endpoint on port 500 to VPN
        // port.
        val udpRuleBldr = redirectRuleBuilder(
            id = Some(JUUID.randomUUID),
            chainId = chainId,
            targetPortId = portId)
        udpRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpDst(RangeUtil.toProto(500, 500)) // IKE UDP port.

        val udp4500RuleBldr = redirectRuleBuilder(
            id = Some(JUUID.randomUUID),
            chainId = chainId,
            targetPortId = portId)
        udp4500RuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpDst(RangeUtil.toProto(4500, 4500)) // IKE Nat traversal

        List(espRuleBldr.build(), udpRuleBldr.build(), udp4500RuleBldr.build())
    }
}

protected[translators] object VpnServiceTranslator {

    /** ID of route directing traffic addressed to 169.254.x.x/30 to the VPN. */
    def vpnContainerRouteId(containerId: UUID): UUID =
        containerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    val VpnServiceType = "IPSEC"

    def isRedirectRule(r: Rule): Boolean = {
        r.getType == Rule.Type.L2TRANSFORM_RULE &&
            r.getAction == Rule.Action.REDIRECT
    }

    def isRedirectForEndpointRule(r: Rule, ip: IPSubnet): Boolean = {
        val isForEndpoint = r.getCondition.hasNwDstIp &&
            r.getCondition.getNwDstIp.equals(ip)
        isRedirectRule(r) && isForEndpoint
    }

    def isESPRule(r: Rule): Boolean = {
        val condition = r.getCondition
        condition.hasNwProto &&
            condition.getNwProto == 50 &&
            !condition.getNwProtoInv
    }

    def isUDP500Rule(r: Rule): Boolean = {
        val condition = r.getCondition
        condition.hasNwProto &&
            condition.getNwProto == 17 &&
            !condition.getNwProtoInv &&
            condition.getTpDst.getStart == 500 &&
            condition.getTpDst.getEnd == 500 &&
            !condition.getTpDstInv
    }

    def isUDP4500Rule(r: Rule): Boolean = {
        val condition = r.getCondition

        condition.hasNwProto &&
            condition.getNwProto == 17 &&
            !condition.getNwProtoInv &&
            condition.getTpDst.getStart == 4500 &&
            condition.getTpDst.getEnd == 4500 &&
            !condition.getTpDstInv
    }

    def updateVpnRedirectRules(newExternalIp: IPAddress, rules: List[Rule]): List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddr(newExternalIp)
        for (rule <- rules) yield {
            val bldr = rule.toBuilder
            bldr.getConditionBuilder.setNwDstIp(localEndpointIp)
            bldr.build()
        }
    }

    def filterVpnRedirectRules(externalIp: IPAddress, rules: Seq[Rule]): List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddr(externalIp)

        rules.filter((r:Rule) => {
                        isRedirectForEndpointRule(r, localEndpointIp) &&
                            (isESPRule(r) || isUDP500Rule(r) || isUDP4500Rule(r))
                    })
            .toList
    }
}

