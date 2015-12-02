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
import org.midonet.cluster.models.Neutron.VPNService
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps

class VPNServiceTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[VPNService]
            with ChainManager with RouteManager {
    import VPNServiceTranslator._

    override protected def translateCreate(vpn: VPNService): MidoOpList = {
        // TODO: Figure out port group ID.
        // TODO: Skip some of this if the router already has a VPNService.

        val rtrId = vpn.getRouterId
        val router = storage.get(classOf[Router], rtrId).await()

        val rtrPort = createRouterPort(vpn)

        val vpnRoute = newNextHopPortRoute(id = vpnLinkLocalRouteId(rtrId),
                                           nextHopPortId = rtrPort.getId,
                                           dstSubnet = VpnLinkLocalSubnet)
        val localRoute = newLocalRoute(rtrPort.getId, rtrPort.getPortAddress)

        val redirectChain = newChain(vpnRedirectChainId(vpn.getRouterId),
                                     redirectChainName(vpn.getRouterId))
        val rtrWithChain = router.toBuilder
            .setLocalRedirectChainId(redirectChain.getId).build()

        val scg = ServiceContainerGroup.newBuilder
            .setId(vpnServiceContainerGroupId(rtrId))
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(vpnServiceContainerId(rtrId))
            .setServiceGroupId(vpn.getId)
            .setPortId(rtrPort.getId)
            .setServiceType("IPSEC") // TODO: Is there a constant for this?
            .setConfigurationId(vpn.getId)
            .build()

        List(Create(redirectChain),
             Update(rtrWithChain),
             Create(rtrPort),
             Create(vpnRoute),
             Create(localRoute),
             Create(scg),
             Create(sc))
    }

    override protected def translateDelete(vpnId: UUID): MidoOpList = {
        // TODO: Don't do this if the router still has another VPNService.
        val vpn = storage.get(classOf[VPNService], vpnId).await()
        List(Delete(classOf[Port], vpnRouterPortId(vpn.getRouterId)),
             Delete(classOf[Chain], vpnRedirectChainId(vpn.getRouterId)),
             Delete(classOf[ServiceContainerGroup],
                    vpnServiceContainerGroupId(vpn.getRouterId)))
    }

    override protected def translateUpdate(vpn: VPNService): MidoOpList = {
        throw new NotImplementedError(
            "Update not supported for VPNService")
    }

    private def createRouterPort(vpn: VPNService): Port = {
        val portId = vpnRouterPortId(vpn.getRouterId)
        Port.newBuilder
            .setId(portId)
            .setRouterId(vpn.getRouterId)
            // TODO: No need to bind port?
//            .setHostId()
//            .setInterfaceName(s"vpn-${"%08x".format(portId.getMsb >>> 32)}_dp")
            .setPortSubnet(VpnLinkLocalSubnet) // TODO: Make sure not taken.
            .setPortAddress(VpnRouterPortAddr) // TODO: This too.
            .setPortMac(MAC.random().toString)
            .build()
    }

}

protected[translators] object VPNServiceTranslator {
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
    def vpnLinkLocalRouteId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def vpnRedirectChainId(routerId: UUID): UUID =
        routerId.xorWith(0xdbbb7a218d014f17L, 0x8cfa272da798414eL)

    def redirectChainName(routerId: UUID): String =
        "VPN_REDIRECT_" + routerId.asJava
}