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

import java.util.UUID

import scala.collection.JavaConversions._

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{IPSecSiteConnection => IpSecSiteConType, VpnService => VpnServiceType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouteManager.localRouteId
import org.midonet.cluster.services.c3po.translators.VpnServiceTranslator._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, toProto}
import org.midonet.util.concurrent.toFutureOps


class VPNaaSTranslatorIT extends C3POMinionTestBase {

    private val extNwId = UUID.randomUUID()

    private val rtr1SnId = UUID.randomUUID()
    private val rtr1SnCidr = "10.0.0.0/24"
    private val rtr1Id = UUID.randomUUID()
    private val rtr1GwPortId = UUID.randomUUID()
    private val rtr1GwIp = "10.0.0.1"
    private val rtr1GwMac = "ab:cd:ef:ab:cd:ef"

    private val vpn1Id = UUID.randomUUID()
    private val vpn2Id = UUID.randomUUID()

    "VpnServiceTranslator" should "handle VPN CRUD" in {
        createTenantNetwork(10, extNwId, external = true)
        setupRouter1(20)

        val vpn1Json = vpnServiceJson(vpn1Id, rtr1Id, rtr1SnId,
                                      externalV4Ip = Some(rtr1GwIp))
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn1Id), rtr1GwIp))

        val vpn2Json = vpnServiceJson(vpn2Id, rtr1Id, rtr1SnId,
                                      externalV4Ip = Some(rtr1GwIp))
        insertCreateTask(40, VpnServiceType, vpn2Json, vpn2Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn1Id, vpn2Id), rtr1GwIp))

        insertDeleteTask(50, VpnServiceType, vpn1Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn2Id), rtr1GwIp))

        val vpn2JsonV2 = vpnServiceJson(vpn2Id, UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        name = Some("renamed-vpn2"),
                                        description = Some("new description"),
                                        adminStateUp = false)
        insertUpdateTask(60, VpnServiceType, vpn2JsonV2, vpn2Id)
        eventually {
            val vpn2 = storage.get(classOf[VpnService], vpn2Id).await()
            vpn2.getAdminStateUp shouldBe false
            vpn2.getName shouldBe "renamed-vpn2"
            vpn2.getDescription shouldBe "new description"

            // Only the above fields should be changed.
            vpn2.getExternalV4Ip.getAddress shouldBe rtr1GwIp
            vpn2.getRouterId.asJava shouldBe rtr1Id
            vpn2.getSubnetId.asJava shouldBe rtr1SnId
        }

        insertDeleteTask(70, VpnServiceType, vpn2Id)
        eventually(verifyNoContainer(rtr1Id))
    }

    "IPSecSiteConnectionTranslator" should "handle IPSecSiteConnection CRUD" in {
        createTenantNetwork(10, extNwId, external = true)
        setupRouter1(20)

        val vpn1Json = vpnServiceJson(vpn1Id, rtr1Id, rtr1SnId,
                                      externalV4Ip = Some(rtr1GwIp))
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)

        // Create and verify a connection.
        val cnxn1Id = UUID.randomUUID()
        val cnxn1LocalCidrs = Seq("10.0.1.0/24", "10.0.2.0/24")
        val cnxn1PeerCidrs = Seq("20.0.1.0/24", "20.0.2.0/24")
        val cnxn1IkeJson = ikePolicyJson()
        val cnxn1IpSecJson = ipSecPolicyJson()
        val cnxn1Json = ipSecSiteConnectionJson(
            cnxn1Id, vpn1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
            cnxn1IkeJson, cnxn1IpSecJson)
        insertCreateTask(40, IpSecSiteConType, cnxn1Json, cnxn1Id)
        val cnxn1 = eventually {
            // We create two routes when we create the VpnService, hence
            // numOtherRoutes = 2
            verifyCnxn(cnxn1Id, rtr1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                       numOtherRoutes = 2)
        }

        // Create a second connection on the same VPN and verify that they
        // coexist.
        val cnxn2Id = UUID.randomUUID()
        val cnxn2LocalCidrs = Seq("30.0.1.0/24", "30.0.2.0/24", "30.0.3.0/24")
        val cnxn2PeerCidrs = Seq("40.0.1.0/24", "40.0.2.0/24", "40.0.2.0/24")
        val cnxn2IkeJson = ikePolicyJson()
        val cnxn2IpSecJson = ipSecPolicyJson()
        val cnxn2Json = ipSecSiteConnectionJson(
            cnxn2Id, vpn1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
            cnxn2IkeJson, cnxn2IpSecJson)
        insertCreateTask(50, IpSecSiteConType, cnxn2Json, cnxn2Id)
        val cnxn2 = eventually {
            verifyCnxn(cnxn2Id, rtr1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
                       numOtherRoutes = 2 + cnxn1.getRouteIdsCount)
        }
        verifyCnxn(cnxn1Id, rtr1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                   numOtherRoutes = 2 + cnxn2.getRouteIdsCount)

        val cnxn1V2Json = ipSecSiteConnectionJson(
            cnxn1Id, vpn1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
            cnxn1IkeJson, cnxn1IpSecJson, adminStateUp = false,
            name = Some("renamed-cnxn1"),
            description = Some("Description for cnxn1"),
            peerId = "ignored", psk = "ignored", status = "error")
        insertUpdateTask(60, IpSecSiteConType, cnxn1V2Json, cnxn1Id)
        eventually {
            val cnxn1V2 = verifyCnxn(
                cnxn1Id, rtr1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                numOtherRoutes = 2 + cnxn2.getRouteIdsCount)
            cnxn1V2.getAdminStateUp shouldBe false
            cnxn1V2.getName shouldBe "renamed-cnxn1"
            cnxn1V2.getDescription shouldBe "Description for cnxn1"

            // Only the above fields should be changed.
            cnxn1V2.getPeerId shouldBe cnxn1.getPeerId
            cnxn1V2.getPsk shouldBe cnxn1.getPsk
            cnxn1V2.getStatus shouldBe cnxn1.getStatus
        }

        // Delete the first connection and verify that it was cleaned up and
        // the second connection remains.
        insertDeleteTask(70, IpSecSiteConType, cnxn1Id)
        eventually {
            verifyCnxnGone(cnxn1Id, rtr1Id, cnxn1.getRouteIdsList,
                           numOtherRoutes = 2 + cnxn2.getRouteIdsCount)
        }
        verifyCnxn(cnxn2Id, rtr1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
                   numOtherRoutes = 2)

        // Delete the second connection.
        insertDeleteTask(80, IpSecSiteConType, cnxn2Id)
        eventually {
            verifyCnxnGone(cnxn2Id, rtr1Id, cnxn2.getRouteIdsList,
                           numOtherRoutes = 2)
        }
    }

    private def verifyCnxn(cnxnId: UUID, rtrId: UUID,
                           localCidrs: Seq[String],
                           peerCidrs: Seq[String],
                           numOtherRoutes: Int): IPSecSiteConnection = {
        val cnxn = storage.get(classOf[IPSecSiteConnection], cnxnId).await()

        val vpnRoutesFtr = storage.getAll(classOf[Route], cnxn.getRouteIdsList)
        val vpnRtrPortFtr = storage.get(classOf[Port], vpnRouterPortId(rtrId))
        val vpnRoutes = vpnRoutesFtr.await()
        val vpnRtrPort = vpnRtrPortFtr.await()

        vpnRoutes.size shouldBe localCidrs.size * peerCidrs.size
        vpnRtrPort.getRouteIdsCount shouldBe numOtherRoutes + vpnRoutes.size

        for (localCidr <- localCidrs; peerCidr <- peerCidrs) {
            val route = vpnRoutes.find { r =>
                IPSubnetUtil.fromProto(r.getSrcSubnet).toString == localCidr &&
                IPSubnetUtil.fromProto(r.getDstSubnet).toString == peerCidr
            }.get
            route.getIpsecSiteConnId.asJava shouldBe cnxnId
            route.getNextHop shouldBe NextHop.PORT
            route.getNextHopGateway shouldBe VpnContainerPortAddr
            route.getNextHopPortId shouldBe vpnRtrPort.getId
            route.hasRouterId shouldBe false
        }
        
        cnxn
    }

    private def verifyCnxnGone(cnxnId: UUID, rtrId: UUID,
                               routeIds: Seq[Commons.UUID],
                               numOtherRoutes: Int): Unit = {
        val rtrPortFtr = storage.get(classOf[Port], vpnRouterPortId(rtrId))
        val cnxnExistsFtr = storage.exists(classOf[IPSecSiteConnection], cnxnId)
        routeIds.map(storage.exists(classOf[Rule], _))
            .exists(_.await() == true) shouldBe false

        cnxnExistsFtr.await() shouldBe false

        val rtrPort = rtrPortFtr.await()
        rtrPort.getRouteIdsCount shouldBe numOtherRoutes
        for (rtId <- routeIds)
            rtrPort.getRouteIdsList should not contain rtId
    }

    private def setupRouter1(firstTaskId: Int, verify: Boolean = true): Unit = {
        createSubnet(firstTaskId, rtr1SnId, extNwId, rtr1SnCidr, rtr1GwIp)
        createRouterGatewayPort(firstTaskId + 1, rtr1GwPortId, extNwId,
                                rtr1Id, rtr1GwIp, rtr1GwMac, rtr1SnId)
        createRouter(firstTaskId + 2, rtr1Id, rtr1GwPortId)
        if (verify) {
            eventually(
                storage.exists(classOf[Router], rtr1Id).await() shouldBe true)
        }
    }

    private def verifyNoContainer(rtrId: UUID): Unit = {
        val rtrFtr = storage.get(classOf[Router], rtrId)
        Seq(storage.exists(classOf[ServiceContainerGroup],
                           vpnServiceContainerGroupId(rtrId)),
            storage.exists(classOf[ServiceContainer],
                           vpnServiceContainerId(rtrId)),
            storage.exists(classOf[Port], vpnRouterPortId(rtrId)),
            storage.exists(classOf[Chain], vpnRedirectChainId(rtrId)),
            storage.exists(classOf[Rule], vpnEspRedirectRuleId(rtrId)),
            storage.exists(classOf[Rule], vpnUdpRedirectRuleId(rtrId)),
            storage.exists(classOf[Route], vpnVpnRouteId(rtrId)),
            storage.exists(classOf[Route],
                           localRouteId(vpnRouterPortId(rtrId))))
            .map(_.await()) shouldBe Seq(false, false, false, false,
                                         false, false, false, false)
        val rtr = rtrFtr.await()
        rtr.hasLocalRedirectChainId shouldBe false
        rtr.getPortIdsList should not contain vpnRouterPortId(toProto(rtrId))
        rtr.getVpnServiceIdsCount shouldBe 0
    }

    private def verifyContainer(rtrId: UUID, vpnIds: Seq[UUID],
                                rtrGwIp: String): Unit = {
        val rtrFtr = storage.get(classOf[Router], rtrId)
        val redirectChainFtr = storage.get(classOf[Chain],
                                           vpnRedirectChainId(rtrId))
        val espRedirectRuleFtr = storage.get(classOf[Rule],
                                             vpnEspRedirectRuleId(rtrId))
        val udpRedirectRuleFtr = storage.get(classOf[Rule],
                                             vpnUdpRedirectRuleId(rtrId))
        val rtrPortFtr = storage.get(classOf[Port], vpnRouterPortId(rtrId))
        val localRtFtr = storage.get(classOf[Route],
                                     localRouteId(vpnRouterPortId(rtrId)))
        val vpnRtFtr = storage.get(classOf[Route], vpnVpnRouteId(rtrId))
        val scgFtr = storage.get(classOf[ServiceContainerGroup],
                                 vpnServiceContainerGroupId(toProto(rtrId)))
        val sc = storage.get(classOf[ServiceContainer],
                             vpnServiceContainerId(toProto(rtrId))).await()
        val rtr = rtrFtr.await()
        val redirectChain = redirectChainFtr.await()
        val espRedirectRule = espRedirectRuleFtr.await()
        val udpRedirectRule = udpRedirectRuleFtr.await()
        val rtrPort = rtrPortFtr.await()
        val localRt = localRtFtr.await()
        val vpnRt = vpnRtFtr.await()
        val scg = scgFtr.await()

        scg.hasHostGroupId shouldBe false
        scg.hasPortGroupId shouldBe false // TODO: Should be true, once implemented.
        scg.getServiceContainerIdsList should contain only sc.getId

        sc.getConfigurationId.asJava shouldBe rtrId
        sc.getPortId shouldBe rtrPort.getId
        sc.getServiceGroupId shouldBe scg.getId
        sc.getServiceType shouldBe "IPSEC"

        rtr.getVpnServiceIdsList.map(_.asJava) should
            contain theSameElementsAs vpnIds
        rtr.getPortIdsList should contain(rtrPort.getId)
        rtr.getLocalRedirectChainId shouldBe redirectChain.getId

        redirectChain.getRuleIdsList should contain only(
            espRedirectRule.getId, udpRedirectRule.getId)
        redirectChain.getRouterRedirectIdsList should
            contain only toProto(rtrId)

        espRedirectRule.getAction shouldBe Rule.Action.REDIRECT
        espRedirectRule.getChainId shouldBe redirectChain.getId
        espRedirectRule.getCondition.getNwDstIp.getAddress shouldBe rtrGwIp
        espRedirectRule.getCondition.getNwProto shouldBe 50
        espRedirectRule.getTransformRuleData.getTargetPortId shouldBe rtrPort.getId
        espRedirectRule.getType shouldBe Rule.Type.L2TRANSFORM_RULE

        udpRedirectRule.getAction shouldBe Rule.Action.REDIRECT
        udpRedirectRule.getChainId shouldBe redirectChain.getId
        udpRedirectRule.getCondition.getNwDstIp.getAddress shouldBe rtrGwIp
        udpRedirectRule.getCondition.getNwProto shouldBe 17
        udpRedirectRule.getCondition.getTpSrc.getStart shouldBe 500
        udpRedirectRule.getCondition.getTpSrc.getEnd shouldBe 500
        udpRedirectRule.getTransformRuleData.getTargetPortId shouldBe rtrPort.getId
        udpRedirectRule.getType shouldBe Rule.Type.L2TRANSFORM_RULE

        rtrPort.getRouterId shouldBe rtr.getId
        rtrPort.getPortAddress shouldBe VpnRouterPortAddr
        rtrPort.getPortSubnet shouldBe VpnLinkLocalSubnet
        rtrPort.getRouteIdsList should contain only(localRt.getId, vpnRt.getId)
        rtrPort.hasPortMac shouldBe true

        localRt.getNextHop shouldBe NextHop.LOCAL
        localRt.getNextHopPortId shouldBe rtrPort.getId
        localRt.hasNextHopGateway shouldBe false
        localRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        localRt.getDstSubnet shouldBe
            IPSubnetUtil.fromAddr(rtrPort.getPortAddress)

        vpnRt.getNextHop shouldBe NextHop.PORT
        vpnRt.getNextHopPortId shouldBe rtrPort.getId
        vpnRt.hasNextHopGateway shouldBe false
        vpnRt.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        vpnRt.getDstSubnet shouldBe VpnLinkLocalSubnet
    }
}
