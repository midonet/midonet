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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{IPSecSiteConnection => IpSecSiteConType, VpnService => VpnServiceType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouteManager.localRouteId
import org.midonet.cluster.services.c3po.translators.VpnServiceTranslator._
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, toProto}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.util.concurrent.toFutureOps


@RunWith(classOf[JUnitRunner])
class VPNaaSTranslatorIT extends C3POMinionTestBase {

    private val externalNetworkId = UUID.randomUUID()

    private val router1SubnetId = UUID.randomUUID()
    private val router1SubnetCidr = "10.0.0.0/24"
    private val router1Id = UUID.randomUUID()
    private val router1GatewayPortId = UUID.randomUUID()
    private val router1GatewayIp = "10.0.0.1"
    private val router1GatewayMac = "ab:cd:ef:ab:cd:ef"

    private val vpn1Id = UUID.randomUUID()
    private val vpn2Id = UUID.randomUUID()

    "VpnServiceTranslator" should "handle VPN CRUD" in {
        createTenantNetwork(10, externalNetworkId, external = true)
        setupRouter1(20)

        // setup first vpn service on router
        val vpn1Json = vpnServiceJson(vpn1Id, router1Id,
                                      externalV4Ip = Some(router1GatewayIp))
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)
        eventually(verifyContainer(router1Id, Seq(vpn1Id), router1GatewayIp))

        // setup second vpn service on router
        val vpn2Json = vpnServiceJson(vpn2Id, router1Id,
                                      externalV4Ip = Some(router1GatewayIp))
        insertCreateTask(40, VpnServiceType, vpn2Json, vpn2Id)
        eventually(verifyContainer(router1Id, Seq(vpn1Id, vpn2Id), router1GatewayIp))

        // delete the first vpn service
        insertDeleteTask(50, VpnServiceType, vpn1Id)
        eventually(verifyContainer(router1Id, Seq(vpn2Id), router1GatewayIp))

        // update the second vpn
        val vpn2JsonV2 = vpnServiceJson(vpn2Id, router1Id,
                                        name = Some("renamed-vpn2"),
                                        description = Some("new description"),
                                        adminStateUp = false,
                                        externalV4Ip = Some(router1GatewayIp))
        insertUpdateTask(60, VpnServiceType, vpn2JsonV2, vpn2Id)

        eventually {
            val vpn2 = storage.get(classOf[VpnService], vpn2Id).await()
            vpn2.getAdminStateUp shouldBe false
            vpn2.getName shouldBe "renamed-vpn2"
            vpn2.getDescription shouldBe "new description"

            // Only the above fields should be changed.
            vpn2.getExternalIp.getAddress shouldBe router1GatewayIp
            vpn2.getRouterId.asJava shouldBe router1Id
        }

        insertDeleteTask(70, VpnServiceType, vpn2Id)
        eventually(verifyNoContainer(router1Id))
    }

    "IPSecSiteConnectionTranslator" should "handle IPSecSiteConnection CRUD" in {
        createTenantNetwork(10, externalNetworkId, external = true)
        setupRouter1(20)

        val vpn1Json = vpnServiceJson(vpn1Id, router1Id,
                                      externalV4Ip = Some(router1GatewayIp))
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)

        // Create and verify a connection.
        val cnxn1Id = UUID.randomUUID()
        var cnxn1LocalCidrs = Seq("10.0.1.0/24")
        var cnxn1PeerCidrs = Seq("20.0.1.0/24", "20.0.2.0/24")
        val cnxn1IkeJson = ikePolicyJson()
        val cnxn1IpSecJson = ipSecPolicyJson()
        val cnxn1Json = ipSecSiteConnectionJson(
            cnxn1Id, vpn1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
            cnxn1IkeJson, cnxn1IpSecJson)
        insertCreateTask(40, IpSecSiteConType, cnxn1Json, cnxn1Id)
        val cnxn1 = eventually {
            // We create two routes when we create the VpnService, hence
            // numOtherRoutes = 2
            verifyCnxn(cnxn1Id, router1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                       numOtherRoutes = 2)
        }

        // Create a second connection on the same VPN and verify that they
        // coexist.
        val cnxn2Id = UUID.randomUUID()
        val cnxn2LocalCidrs = Seq("10.0.1.0/24", "10.0.2.0/24")
        val cnxn2PeerCidrs = Seq("40.0.1.0/24", "40.0.2.0/24", "40.0.3.0/24")
        val cnxn2IkeJson = ikePolicyJson()
        val cnxn2IpSecJson = ipSecPolicyJson()
        val cnxn2Json = ipSecSiteConnectionJson(
            cnxn2Id, vpn1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
            cnxn2IkeJson, cnxn2IpSecJson)
        insertCreateTask(50, IpSecSiteConType, cnxn2Json, cnxn2Id)
        val cnxn2 = eventually {
            verifyCnxn(cnxn2Id, router1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
                       numOtherRoutes = 2 + cnxn1.getRouteIdsCount)
        }
        verifyCnxn(cnxn1Id, router1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                   numOtherRoutes = 2 + cnxn2.getRouteIdsCount)

        // Update cidrs so 1 route is kept, 1 route is deleted, 1 route is added
        cnxn1LocalCidrs = Seq("10.0.1.0/24")
        cnxn1PeerCidrs = Seq("20.0.1.0/24", "20.0.3.0/24")
        val cnxn1V2Json = ipSecSiteConnectionJson(
            cnxn1Id, vpn1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
            cnxn1IkeJson, cnxn1IpSecJson, adminStateUp = false,
            name = Some("renamed-cnxn1"),
            description = Some("Description for cnxn1"))
        insertUpdateTask(60, IpSecSiteConType, cnxn1V2Json, cnxn1Id)
        eventually {
            val cnxn1V2 = verifyCnxn(
                cnxn1Id, router1Id, cnxn1LocalCidrs, cnxn1PeerCidrs,
                numOtherRoutes = 2 + cnxn2.getRouteIdsCount)
            cnxn1V2.getAdminStateUp shouldBe false
            cnxn1V2.getName shouldBe "renamed-cnxn1"
            cnxn1V2.getDescription shouldBe "Description for cnxn1"

            // Only the above fields should be changed.
            cnxn1V2.getPeerId shouldBe cnxn1.getPeerId
            cnxn1V2.getPsk shouldBe cnxn1.getPsk
        }

        // Delete the first connection and verify that it was cleaned up and
        // the second connection remains.
        insertDeleteTask(70, IpSecSiteConType, cnxn1Id)
        eventually {
            verifyCnxnGone(cnxn1Id, cnxn1.getRouteIdsList)
        }
        verifyCnxn(cnxn2Id, router1Id, cnxn2LocalCidrs, cnxn2PeerCidrs,
                   numOtherRoutes = 2)

        // Delete the second connection.
        insertDeleteTask(80, IpSecSiteConType, cnxn2Id)
        eventually {
            verifyCnxnGone(cnxn2Id, cnxn2.getRouteIdsList)
        }
    }

    "IPSecSiteConnectionTranslator" should "not delete preexisting local redirect chain" in {
        createTenantNetwork(10, externalNetworkId, external = true)
        setupRouter1(20)

        val router = storage.get(classOf[Router], router1Id).await()
        val chain = Chain.newBuilder()
            .setId(UUID.randomUUID)
            .setName("PREEXISTING_CHAIN")
            .build()
        storage.create(chain)
        val rule = Rule.newBuilder()
            .setId(UUID.randomUUID)
            .setChainId(chain.getId)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.DROP)
            .build()
        storage.create(rule)
        storage.update(router.toBuilder
                           .setLocalRedirectChainId(chain.getId).build)

        val vpn1Json = vpnServiceJson(vpn1Id, router1Id,
                                      externalV4Ip = Some(router1GatewayIp))
        insertCreateTask(30, VpnServiceType, vpn1Json, vpn1Id)
        eventually(verifyContainer(router1Id, Seq(vpn1Id),
                                   router1GatewayIp))
        insertDeleteTask(40, VpnServiceType, vpn1Id)
        storage.exists(classOf[Chain], chain.getId).await() shouldBe true

        storage.delete(classOf[Rule], rule.getId)

        storage.exists(classOf[Chain], chain.getId).await() shouldBe true

        insertCreateTask(50, VpnServiceType, vpn1Json, vpn1Id)
        eventually(verifyContainer(router1Id, Seq(vpn1Id),
                                   router1GatewayIp))
        insertDeleteTask(60, VpnServiceType, vpn1Id)
        eventually {
            storage.exists(classOf[Chain], chain.getId).await() shouldBe false
        }
    }

    private def verifyCnxn(cnxnId: UUID, rtrId: UUID,
                           localCidrs: Seq[String],
                           peerCidrs: Seq[String],
                           numOtherRoutes: Int): IPSecSiteConnection = {
        val cnxn = storage.get(classOf[IPSecSiteConnection], cnxnId).await()

        val vpnService = storage.get(classOf[VpnService],
                                     cnxn.getVpnserviceId).await()
        val container = storage.get(classOf[ServiceContainer],
                                    vpnService.getContainerId).await()
        val vpnRoutes = storage.getAll(classOf[Route],
                                       cnxn.getRouteIdsList).await()
        val vpnRtrPort = storage.get(classOf[Port], container.getPortId).await()

        vpnRoutes.size shouldBe localCidrs.size * peerCidrs.size
        vpnRtrPort.getRouteIdsCount shouldBe numOtherRoutes + vpnRoutes.size

        for (localCidr <- localCidrs; peerCidr <- peerCidrs) {
            val route = vpnRoutes.find { r =>
                IPSubnetUtil.fromProto(r.getSrcSubnet).toString == localCidr &&
                IPSubnetUtil.fromProto(r.getDstSubnet).toString == peerCidr
            }.get
            route.getIpsecSiteConnectionId.asJava shouldBe cnxnId
            route.getNextHop shouldBe NextHop.PORT
            route.getNextHopGateway shouldBe IPAddressUtil.toProto("169.254.0.2")
            route.getNextHopPortId shouldBe vpnRtrPort.getId
            route.hasRouterId shouldBe false
        }
        cnxn
    }

    private def verifyCnxnGone(cnxnId: UUID,
                               routeIds: Seq[Commons.UUID]): Unit = {
        routeIds.map(storage.exists(classOf[Route], _))
            .exists(_.await() == true) shouldBe false

        storage.exists(classOf[IPSecSiteConnection], cnxnId)
            .await() shouldBe false
    }

    private def setupRouter1(firstTaskId: Int): Unit = {
        createSubnet(firstTaskId, externalNetworkId,
                     router1SubnetCidr, router1SubnetId, router1GatewayIp)
        createRouterGatewayPort(firstTaskId + 1, externalNetworkId,
                                router1GatewayIp, router1GatewayMac,
                                router1SubnetId, id = router1GatewayPortId)
        createRouter(firstTaskId + 2, router1Id, router1GatewayPortId)
        eventually(storage.exists(classOf[Router],
                                  router1Id).await() shouldBe true)
    }

    private def verifyNoContainer(routerId: UUID): Unit = {
        storage.getAll(classOf[ServiceContainerGroup]).await().size shouldBe 0
        storage.getAll(classOf[ServiceContainer]).await().size shouldBe 0

        val router = storage.get(classOf[Router], routerId).await()
        router.getPortIdsCount shouldBe 1 // only gw left

        val rules = storage.getAll(classOf[Rule]).await()
            .filter(VpnServiceTranslator.isRedirectRule)
        rules.count(VpnServiceTranslator.isESPRule) shouldBe 0
        rules.count(VpnServiceTranslator.isUDP500Rule) shouldBe 0
        rules.count(VpnServiceTranslator.isUDP4500Rule) shouldBe 0

        router.hasLocalRedirectChainId shouldBe false
        router.getVpnServiceIdsCount shouldBe 0
    }

    private def verifyContainer(rtrId: UUID, vpnIds: Seq[UUID],
                                rtrGwIp: String): Unit = {
        val router = storage.get(classOf[Router], rtrId).await()
        router.hasLocalRedirectChainId shouldBe true

        val containerIds = storage.getAll(classOf[VpnService], vpnIds).await()
            .map(_.getContainerId).distinct
        containerIds.size shouldBe 1
        val container = storage.get(classOf[ServiceContainer],
                                    containerIds(0)).await()
        val rtrPort = storage.get(classOf[Port], container.getPortId).await()
        val redirectChain = storage.get(classOf[Chain],
                                        router.getLocalRedirectChainId).await()

        val localEndpointIp = IPSubnetUtil.fromAddress(rtrGwIp)
        val rules = storage.getAll(classOf[Rule],
                                   redirectChain.getRuleIdsList).await()
            .filter((r: Rule) => {
                        VpnServiceTranslator.isRedirectForEndpointRule(r, localEndpointIp) &&
                            r.getTransformRuleData.getTargetPortId == rtrPort.getId
                    })
        rules.count(VpnServiceTranslator.isESPRule) shouldBe 1
        rules.count(VpnServiceTranslator.isUDP500Rule) shouldBe 1
        rules.count(VpnServiceTranslator.isUDP4500Rule) shouldBe 1

        val localRt = storage.get(classOf[Route],
                                     localRouteId(container.getPortId)).await()
        val vpnRt = storage.get(classOf[Route],
                                vpnContainerRouteId(container.getId)).await()
        val scg = storage.get(classOf[ServiceContainerGroup],
                              container.getServiceGroupId).await()

        scg.hasHostGroupId shouldBe false
        scg.hasPortGroupId shouldBe false // TODO: Should be true, once implemented.
        scg.getServiceContainerIdsList should contain only container.getId

        container.getConfigurationId.asJava shouldBe rtrId
        container.getPortId shouldBe rtrPort.getId
        container.getServiceGroupId shouldBe scg.getId
        container.getServiceType shouldBe "IPSEC"

        router.getVpnServiceIdsList.map(_.asJava) should
            contain theSameElementsAs vpnIds
        router.getPortIdsList should contain(rtrPort.getId)
        router.getLocalRedirectChainId shouldBe redirectChain.getId

        redirectChain.getRouterRedirectIdsList should
            contain only toProto(rtrId)

        rtrPort.getRouterId shouldBe router.getId
        rtrPort.getPortAddress shouldBe IPAddressUtil.toProto("169.254.0.1")
        rtrPort.getPortSubnet(0) shouldBe IPSubnetUtil.toProto("169.254.0.1/30")
        rtrPort.getRouteIdsList should contain only(localRt.getId, vpnRt.getId)
        rtrPort.hasPortMac shouldBe true

        localRt.getNextHop shouldBe NextHop.LOCAL
        localRt.getNextHopPortId shouldBe rtrPort.getId
        localRt.hasNextHopGateway shouldBe false
        localRt.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        localRt.getDstSubnet shouldBe
            IPSubnetUtil.fromAddress(rtrPort.getPortAddress)

        vpnRt.getNextHop shouldBe NextHop.PORT
        vpnRt.getNextHopPortId shouldBe rtrPort.getId
        vpnRt.hasNextHopGateway shouldBe false
        vpnRt.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        vpnRt.getDstSubnet shouldBe IPSubnetUtil.toProto("169.254.0.0/30")
    }
}
