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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{VPNService, IPSecSiteConnection}
import org.midonet.cluster.services.c3po.midonet.Create
import org.midonet.cluster.util.{RangeUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

class IPSecSiteConnectionTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[IPSecSiteConnection]
            with RouteManager
            with RuleManager {
    import IPSecSiteConnectionTranslator._

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(cnxn: IPSecSiteConnection)
    : MidoOpList = {
        val vpn = storage.get(classOf[VPNService], cnxn.getVpnServiceId).await()
        createRemoteRouteOps(cnxn, vpn) ++ createRedirectRules(cnxn, vpn)
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        List()
    }

    override protected def translateUpdate(cnxn: IPSecSiteConnection)
    : MidoOpList = {
        throw new NotImplementedError(
            "Update not supported for IPSecSiteConnection")
    }

    /** Generate options to create routes for Cartesian product of cnxn's
      * local CIDRs and peer CIDRs.
      */
    private def createRemoteRouteOps(cnxn: IPSecSiteConnection,
                                     vpn: VPNService): MidoOpList = {
        val rtrPortId = VPNServiceTranslator.vpnRouterPortId(vpn.getRouterId)
        for (localCidr <- cnxn.getLocalCidrsList.toList;
             peerCidr <- cnxn.getPeerCidrsList) yield {
            Create(
                newNextHopPortRoute(
                    id = UUIDUtil.randomUuidProto,
                    nextHopPortId = rtrPortId,
                    nextHopGwIpAddr = VPNServiceTranslator.VpnContainerPortAddr,
                    srcSubnet = localCidr,
                    dstSubnet = peerCidr))
        }
    }

    private def createRedirectRules(cnxn: IPSecSiteConnection,
                                    vpn: VPNService): MidoOpList = {
        val rtrId = vpn.getRouterId
        val redirectChainId = VPNServiceTranslator.vpnRedirectChainId(rtrId)
        val rtrPortId = VPNServiceTranslator.vpnRouterPortId(rtrId)

        // TODO: Support IPv6
        val localEndpointIp = IPSubnetUtil.fromAddr(vpn.getExternalV4Ip)

        // Redirect ESP traffic addressed to local endpoint to VPN port.
        val espRuleBldr = redirectRuleBuilder(
            id = vpnEspRedirectRuleId(cnxn.getId),
            chainId = redirectChainId,
            targetPortId = rtrPortId)
        espRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(50) // ESP

        // Redirect UDP traffic addressed to local endpoint on port 500 to VPN
        // port.
        val udpRuleBldr = redirectRuleBuilder(
            id = vpnUdpRedirectRuleId(cnxn.getId),
            chainId = redirectChainId,
            targetPortId = rtrPortId)
        udpRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpSrc(RangeUtil.toProto(500, 500)) // TODO: Why 500?

        List(Create(espRuleBldr.build()),
             Create(udpRuleBldr.build()))
    }

    // TODO: Probably won't use this. Instead just give the routes random
    // UUIDs and use Zoom bindings for referential integrity.
//    private def makeRouteId(rtrPortId: UUID,
//                            localCidr: IPSubnet,
//                            peerCidr: IPSubnet): UUID = {
//        def getBytes(ipAddr: String) = IPAddr.fromString(ipAddr).toBytes
//        val bytes = ByteBuffer.allocate(50)
//            .putLong(rtrPortId.getMsb)
//            .putLong(rtrPortId.getLsb)
//            .put(getBytes(VPNServiceTranslator.VpnContainerPortAddr.getAddress))
//            .put(getBytes(localCidr.getAddress))
//            .putInt(localCidr.getPrefixLength)
//            .put(getBytes(peerCidr.getAddress))
//            .putInt(peerCidr.getPrefixLength)
//            .array()
//        UUIDUtil.toProto(util.UUID.nameUUIDFromBytes(bytes))
//    }
}

protected[translators] object IPSecSiteConnectionTranslator {
    /** Generate ID for rule to redirect ESP traffic to VPN port. */
    def vpnEspRedirectRuleId(cnxnId: UUID): UUID =
        cnxnId.xorWith(0xebcf3f9c57b0445bL, 0x911173b93be7220cL)

    /** Generate ID for rule to redirect UDP traffic on port 500 to VPN port. */
    def vpnUdpRedirectRuleId(cnxnId: UUID): UUID =
        cnxnId.xorWith(0x8f32d38b951946d8L, 0xac6fa76e094db10eL)
}

