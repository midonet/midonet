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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, VpnService}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, SequenceDispenser}
import org.midonet.containers
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps


class VpnServiceTranslator(protected val storage: ReadOnlyStorage,
                           sequenceDispenser: SequenceDispenser)
    extends Translator[VpnService]
        with ChainManager
        with PortManager
        with RouteManager
        with RuleManager {
    import VpnServiceTranslator._

    override protected def translateCreate(vpn: VpnService): OperationList = {
        val routerId = vpn.getRouterId
        val router = storage.get(classOf[Router], routerId).await()

        // Nothing to do if the router already has a VPNService.
        // We only support one container per router now
        val existing = storage.getAll(classOf[VpnService],
                                      router.getVpnServiceIdsList).await()
        existing.headOption match {
            case Some(existingVpnService) =>
                return List(Update(vpn.toBuilder()
                                       .setContainerId(existingVpnService.getContainerId)
                                       .setExternalIp(existingVpnService.getExternalIp)
                                       .build()))
            case None =>
        }

        val neutronRouter = storage.get(classOf[NeutronRouter], routerId).await()
        if (!neutronRouter.hasGwPortId) {
            throw new IllegalArgumentException(
                "Cannot discover gateway port of router")
        }
        val neutronGatewayPort = storage.get(classOf[NeutronPort],
                                             neutronRouter.getGwPortId).await()
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
        val routerPort = createRouterPort(router)

        val vpnRoute = newNextHopPortRoute(id = vpnContainerRouteId(containerId),
                                           nextHopPortId = routerPort.getId,
                                           dstSubnet = routerPort.getPortSubnet)
        val localRoute = newLocalRoute(routerPort.getId,
                                       routerPort.getPortAddress)

        val (chainId, chainOps) = if (router.hasLocalRedirectChainId) {
            (router.getLocalRedirectChainId, List())
        } else {
            val id = JUUID.randomUUID
            val chain = newChain(id, "LOCAL_REDIRECT_" + routerId.asJava)

            // need to add the backreference again, because this write will
            // overwrite the zoom write which adds the automatic backreference.
            // This will not be a problem once we are using transactions here
            val routerWithChain = router.toBuilder
                .setLocalRedirectChainId(id)
                .addVpnServiceIds(vpn.getId)
                .build()

            (id.asProto, List(Create(chain), Update(routerWithChain)))
        }

        val redirectRules = makeRedirectRules(
            chainId, externalIp, routerPort.getId)

        // TODO: Set port group ID (MI-300).
        val scg = ServiceContainerGroup.newBuilder
            .setId(JUUID.randomUUID)
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(containerId)
            .setServiceGroupId(scg.getId)
            .setPortId(routerPort.getId)
            .setServiceType(VpnServiceType)
            .setConfigurationId(routerId)
            .build()


        val modifiedVpnService = vpn.toBuilder()
            .setContainerId(sc.getId)
            .setExternalIp(externalIp)
            .build()

        chainOps ++ redirectRules.map(Create(_)) ++
            List(Create(routerPort),
                 Create(vpnRoute),
                 Create(localRoute),
                 Create(scg),
                 Create(sc)) ++
            List(Update(modifiedVpnService))
    }

    override protected def translateDelete(vpnId: UUID): OperationList = {
        val vpn = storage.get(classOf[VpnService], vpnId).await()
        val router = storage.get(classOf[Router], vpn.getRouterId).await()

        val otherServices = storage.getAll(classOf[VpnService],
                                           router.getVpnServiceIdsList).await()
            .count((other: VpnService) => {
                       vpn.getContainerId == other.getContainerId &&
                       vpn.getId != other.getId
                   })
        if (otherServices > 0) {
            List() // do nothing
        } else {
            val chainOps = if (router.hasLocalRedirectChainId) {
                val chainId = router.getLocalRedirectChainId
                val chain = storage.get(classOf[Chain], chainId).await()
                val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList()).await()
                val rulesToDelete = filterVpnRedirectRules(vpn.getExternalIp, rules)
                    .map(_.getId)
                if (rulesToDelete.size == chain.getRuleIdsCount) {
                    List(Update(router.toBuilder
                                    .clearLocalRedirectChainId()
                                    .clearVpnServiceIds()
                                    .build()),
                         Delete(classOf[Chain], chainId))
                } else { // just delete the rules
                    rulesToDelete.map(Delete(classOf[Rule], _))
                }
            } else {
                List() // no chain ops
            }

            val container = storage.get(classOf[ServiceContainer],
                                        vpn.getContainerId).await()
            chainOps ++ List(
                Delete(classOf[Port], container.getPortId),
                Delete(classOf[ServiceContainerGroup],
                       container.getServiceGroupId))
        }
    }

    override protected def translateUpdate(vpn: VpnService): OperationList = {
        // No Midonet-specific changes, but changes to the VPNService are
        // handled in retainHighLevelModel().
        List()
    }

    override protected def retainHighLevelModel(op: Operation[VpnService])
    : List[Operation[VpnService]] = op match {
        case Update(vpn, _) =>
            // Need to override update to make sure only certain fields are
            // updated, to avoid overwriting ipsec_site_conn_ids, which Neutron
            // doesn't know about.
            val oldVpn = storage.get(classOf[VpnService], vpn.getId).await()
            val newVpn = vpn.toBuilder()
                .addAllIpsecSiteConnectionIds(oldVpn.getIpsecSiteConnectionIdsList)
                .setContainerId(oldVpn.getContainerId)
                .setExternalIp(oldVpn.getExternalIp)
                .build()
            List(Update(newVpn))
        case _ => super.retainHighLevelModel(op)
    }

    @throws[NoSuchElementException]
    private def createRouterPort(router: Router): Port = {
        val currentPorts = storage
            .getAll(classOf[Port], router.getPortIdsList).await()

        val subnet = containers.findLocalSubnet(currentPorts)
        val routerAddr = containers.routerPortAddress(subnet)

        val portId = JUUID.randomUUID
        val builder = Port.newBuilder
            .setId(portId)
            .setRouterId(router.getId)
            .setPortSubnet(subnet)
            .setPortAddress(routerAddr)
            .setPortMac(MAC.random().toString)
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
        val condition = r.getCondition
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

