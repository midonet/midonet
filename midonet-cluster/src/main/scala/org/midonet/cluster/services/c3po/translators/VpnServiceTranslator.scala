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
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.VpnService
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, RangeUtil}
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps

class VpnServiceTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[VpnService]
            with ChainManager with RouteManager with RuleManager {
    import VpnServiceTranslator._

    override protected def translateCreate(vpn: VpnService): OperationList = {
        // TODO: Figure out port group ID.

        val rtrId = vpn.getRouterId
        val router = storage.get(classOf[Router], rtrId).await()

        // Nothing to do if the router already has a VPNService.
        if (router.getVpnServiceIdsCount > 0) return List()

        val rtrPort = createRouterPort(rtrId)

        val vpnRoute = newNextHopPortRoute(id = vpnVpnRouteId(rtrId),
                                           nextHopPortId = rtrPort.getId,
                                           dstSubnet = VpnLinkLocalSubnet)
        val localRoute = newLocalRoute(rtrPort.getId, rtrPort.getPortAddress)

        val redirectChain = newChain(id = vpnRedirectChainId(rtrId),
                                     name = vpnRedirectChainName(rtrId))
        val redirectRules = makeRedirectRules(vpn)

        val rtrWithChain = router.toBuilder
            .setLocalRedirectChainId(redirectChain.getId)
            .addVpnServiceIds(vpn.getId)
            .build()

        val scg = ServiceContainerGroup.newBuilder
            .setId(vpnServiceContainerGroupId(rtrId))
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(vpnServiceContainerId(rtrId))
            .setServiceGroupId(vpnServiceContainerGroupId(rtrId))
            .setPortId(rtrPort.getId)
            .setServiceType("IPSEC")
            .setConfigurationId(rtrId)
            .build()

        List(Create(redirectChain),
             Create(redirectRules.espRedirectRule),
             Create(redirectRules.udpRedirectRule),
             Update(rtrWithChain),
             Create(rtrPort),
             Create(vpnRoute),
             Create(localRoute),
             Create(scg),
             Create(sc))
    }

    override protected def translateDelete(vpnId: UUID): OperationList = {
        val vpn = storage.get(classOf[VpnService], vpnId).await()
        val router = storage.get(classOf[Router], vpn.getRouterId).await()
        if (router.getVpnServiceIdsCount > 1) List() else {
            val updatedRouter = router.toBuilder
                .clearLocalRedirectChainId()
                .clearVpnServiceIds()
                .build()
            List(Update(updatedRouter),
                 Delete(classOf[Port], vpnRouterPortId(vpn.getRouterId)),
                 Delete(classOf[Chain], vpnRedirectChainId(vpn.getRouterId)),
                 Delete(classOf[ServiceContainerGroup],
                        vpnServiceContainerGroupId(vpn.getRouterId)))
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
            val newVpn = oldVpn.toBuilder
                .setAdminStateUp(vpn.getAdminStateUp)
                .setDescription(vpn.getDescription)
                .setName(vpn.getName)
            List(Update(newVpn.build()))
        case _ => super.retainHighLevelModel(op)
    }

    private def createRouterPort(rtrId: UUID): Port = {
        val portId = vpnRouterPortId(rtrId)
        Port.newBuilder
            .setId(portId)
            .setRouterId(rtrId)
            .setPortSubnet(VpnLinkLocalSubnet) // TODO: Make sure not taken.
            .setPortAddress(VpnRouterPortAddr) // TODO: This too.
            .setPortMac(MAC.random().toString)
            .build()
    }


    private case class RedirectRules(espRedirectRule: Rule,
                                     udpRedirectRule: Rule)
    private def makeRedirectRules(vpn: VpnService): RedirectRules = {
        val rtrId = vpn.getRouterId
        val redirectChainId = VpnServiceTranslator.vpnRedirectChainId(rtrId)
        val rtrPortId = VpnServiceTranslator.vpnRouterPortId(rtrId)

        val localEndpointIp = IPSubnetUtil.fromAddr(
            if (vpn.hasExternalV4Ip) vpn.getExternalV4Ip
            else vpn.getExternalV6Ip)

        // Redirect ESP traffic addressed to local endpoint to VPN port.
        val espRuleBldr = redirectRuleBuilder(
            id = vpnEspRedirectRuleId(rtrId),
            chainId = redirectChainId,
            targetPortId = rtrPortId)
        espRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(50) // ESP

        // Redirect UDP traffic addressed to local endpoint on port 500 to VPN
        // port.
        val udpRuleBldr = redirectRuleBuilder(
            id = vpnUdpRedirectRuleId(rtrId),
            chainId = redirectChainId,
            targetPortId = rtrPortId)
        udpRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpSrc(RangeUtil.toProto(500, 500)) // IKE UDP port.

        RedirectRules(espRuleBldr.build(), udpRuleBldr.build())
    }
}

protected[translators] object VpnServiceTranslator {
    val VpnLinkLocalSubnet = IPSubnetUtil.toProto("169.254.1.0/30")
    val VpnRouterPortAddr = IPAddressUtil.toProto("169.254.1.1")
    val VpnContainerPortAddr = IPAddressUtil.toProto("169.254.1.2")

    /** ID of the VPN service container for the specified router. */
    def vpnServiceContainerId(routerId: UUID): UUID =
        routerId.xorWith(0xa05eee8aa3564334L, 0xb978d7152a3fd31fL)

    /** ID of the VPN service container group for the specified router. */
    def vpnServiceContainerGroupId(routerId: UUID): UUID =
        routerId.xorWith(0x56ea29a5fff4480cL, 0xab4d4a564a4d3782L)

    /** ID of the router port generated for the specified router. */
    def vpnRouterPortId(routerId: UUID): UUID =
        routerId.xorWith(0x94a601d7bcbb458aL, 0xa00bb8bcb818fc45L)

    /** ID of route directing traffic addressed to 169.254.1.0/30 to the VPN. */
    def vpnVpnRouteId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    /** Generate ID for router's VPN redirect chain. */
    def vpnRedirectChainId(routerId: UUID): UUID =
        routerId.xorWith(0xdbbb7a218d014f17L, 0x8cfa272da798414eL)

    /** Generate name for router's VPN redirect chain. */
    def vpnRedirectChainName(routerId: UUID): String =
        "VPN_REDIRECT_" + routerId.asJava

    /** Generate ID for rule to redirect ESP traffic to VPN port. */
    def vpnEspRedirectRuleId(rtrId: UUID): UUID =
        rtrId.xorWith(0xebcf3f9c57b0445bL, 0x911173b93be7220cL)

    /** Generate ID for rule to redirect UDP traffic on port 500 to VPN port. */
    def vpnUdpRedirectRuleId(rtrId: UUID): UUID =
        rtrId.xorWith(0x8f32d38b951946d8L, 0xac6fa76e094db10eL)
}