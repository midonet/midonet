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
import org.midonet.cluster.models.Neutron.VpnService
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

    "VPNService translator" should "handle VPN CRUD" in {
        createTenantNetwork(10, extNwId, external = true)
        setupRouter1(20)

        val vpn1Json = vpnServiceJson(vpn1Id, rtr1Id, rtr1SnId,
                                      externalV4Ip = rtr1GwIp)
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn1Id), rtr1GwIp))

        val vpn2Json = vpnServiceJson(vpn2Id, rtr1Id, rtr1SnId,
                                      externalV4Ip = rtr1GwIp)
        insertCreateTask(40, VpnServiceType, vpn2Json, vpn2Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn1Id, vpn2Id), rtr1GwIp))

        insertDeleteTask(50, VpnServiceType, vpn1Id)
        eventually(verifyContainer(rtr1Id, Seq(vpn2Id), rtr1GwIp))

        val vpn2JsonV2 = vpnServiceJson(vpn2Id, UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        name = "renamed-vpn2",
                                        description = "new description",
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
            storage.exists(classOf[Route], vpnVpnRouteId(rtrId)),
            storage.exists(classOf[Route],
                           localRouteId(vpnRouterPortId(rtrId))))
            .map(_.await()) shouldBe Seq(false, false, false,
                                         false, false, false)
        val rtr = rtrFtr.await()
        rtr.hasLocalRedirectChainId shouldBe false
        rtr.getPortIdsList should not contain vpnRouterPortId(toProto(rtrId))
        rtr.getVpnServiceIdsCount shouldBe 0
    }

    private def verifyContainer(rtrId: UUID, vpnIds: Seq[UUID],
                                rtrGwIp: String): Unit = {
        val rtrFtr = storage.get(classOf[Router], rtrId)
        val rtrPortFtr = storage.get(classOf[Port], vpnRouterPortId(rtrId))
        val localRtFtr = storage.get(classOf[Route],
                                     localRouteId(vpnRouterPortId(rtrId)))
        val vpnRtFtr = storage.get(classOf[Route], vpnVpnRouteId(rtrId))
        val scgFtr = storage.get(classOf[ServiceContainerGroup],
                                 vpnServiceContainerGroupId(toProto(rtrId)))
        val sc = storage.get(classOf[ServiceContainer],
                             vpnServiceContainerId(toProto(rtrId))).await()
        val rtr = rtrFtr.await()
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
        rtr.hasLocalRedirectChainId shouldBe true
        storage.exists(classOf[Chain],
                       rtr.getLocalRedirectChainId).await() shouldBe true

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
