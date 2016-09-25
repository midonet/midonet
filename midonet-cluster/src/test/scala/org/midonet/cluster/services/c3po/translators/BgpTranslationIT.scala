/*
 * Copyright 2016 Midokura SARL
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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConverters._

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{BgpPeer => BgpPeerType, BgpSpeaker => BgpSpeakerType, Port => PortType, Router => RouterType}
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronRoute, NeutronRouter}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.services.c3po.translators.RouteManager.extraRouteId
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Subnet, TCP}
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class BgpTranslationIT extends C3POMinionTestBase {

    import BgpPeerTranslator._
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "BgpPeerTranslator" should "create Quagga container for router's first " +
                               "peer" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        eventually {
            val peerPortId = routerInterfacePortPeerId(rifPortId.asProto)
            storage.exists(classOf[Port], peerPortId).await() shouldBe true
        }

        checkNoQuaggaContainer(rtrId)

        val peerId = createBgpPeer(30, rtrId, "20.0.0.1")
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
        }

        val peer2Id = createBgpPeer(40, rtrId, "30.0.0.1")
        eventually(checkBgpPeer(rtrId, peer2Id))

        storage.getAll(classOf[ServiceContainer]).await().size shouldBe 1
    }

    it should "not delete Quagga container when router's last peer is " +
              "deleted" in {
        val rtrId = createRouter(10)
        createNetworkAndRouterInterface(20, rtrId, "10.0.0.0/24", "10.0.0.1")

        // Create two peers.
        val peerId = createBgpPeer(30, rtrId, "20.0.0.1")
        val peer2Id = createBgpPeer(40, rtrId, "30.0.0.1")
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
        }

        // Delete one peer. Container should remain.
        insertDeleteTask(50, BgpPeerType, peerId)
        eventually {
            checkNoBgpPeer(rtrId, peerId)
            checkQuaggaContainer(rtrId)
        }

        // Delete the other peer. Container should be deleted.
        insertDeleteTask(60, BgpPeerType, peer2Id)
        eventually {
            checkNoBgpPeer(rtrId, peer2Id)
            checkQuaggaContainer(rtrId)
        }
    }

    it should "update BgpPeer's password" in {
        val rtrId = createRouter(10)
        createNetworkAndRouterInterface(20, rtrId, "10.0.0.0/24", "10.0.0.1")

        val peerId = createBgpPeer(30, rtrId, "20.0.0.1", password = "password")
        eventually(checkBgpPeer(rtrId, peerId))

        // Update password.
        val speakerJson = bgpSpeakerJson(rtrId)
        val updatedPeerJson = bgpPeerJson("20.0.0.1", speakerJson, id = peerId,
                                          password = "p@ssword") // more secure
        insertUpdateTask(40, BgpPeerType, updatedPeerJson, peerId)
        eventually {
            val mPeer = storage.get(classOf[BgpPeer], peerId).await()
            mPeer.getPassword shouldBe "p@ssword"
            val nPeer = storage.get(classOf[NeutronBgpPeer], peerId).await()
            nPeer.getPassword shouldBe "p@ssword"
        }
    }

    it should "create BgpNetworks for all router interfaces" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val rifPort2Id = createNetworkAndRouterInterface(
            30, rtrId, "10.0.1.0/24", "10.0.1.1")
        eventually {
            val peerPortId =
                routerInterfacePortPeerId(rifPort2Id.asProto)
            storage.exists(classOf[Port], peerPortId).await() shouldBe true
        }

        checkNoBgpNetwork(rifPortId)
        checkNoBgpNetwork(rifPort2Id)

        createBgpPeer(40, rtrId, "20.0.0.1")
        eventually {
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }
    }

    it should "not delete all BgpNetworks when the router's last BgpPeer is " +
              "deleted" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val rifPort2Id = createNetworkAndRouterInterface(
            30, rtrId, "10.0.1.0/24", "10.0.1.1")

        // Create two peers.
        val peerId = createBgpPeer(40, rtrId, "20.0.0.1")
        val peer2Id = createBgpPeer(50, rtrId, "30.0.0.1")
        eventually {
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }

        // Delete one peer. Networks should not be deleted.
        insertDeleteTask(60, BgpPeerType, peerId)
        eventually {
            checkNoBgpPeer(rtrId, peerId)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }

        // Delete the second peer. Networks should be deleted.
        insertDeleteTask(70, BgpPeerType, peer2Id)
        eventually {
            checkNoBgpPeer(rtrId, peer2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }
    }

    "RouterInterfaceTranslator" should
    "create a BgpNetwork when adding a RouterInterface to a router with " +
    "at least one BgpPeer" in {
        val rtrId = createRouter(10)
        val peerId = createBgpPeer(20, rtrId, "20.0.0.1")
        eventually {
            storage.exists(classOf[BgpPeer], peerId).await() shouldBe true
            storage.get(classOf[Router], rtrId).await()
                .getBgpNetworkIdsCount shouldBe 0
        }

        val rifPortId = createNetworkAndRouterInterface(
            30, rtrId, "10.0.0.0/24", "10.0.0.1")
        eventually {
            val bgpNwId = bgpNetworkId(rifPortId.asProto)
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpNetworkIdsList should contain only bgpNwId
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
        }
    }

    "PortTranslator" should "delete a BgpNetwork when deleting the " +
                            "corresponding RouterInterface" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val bgpNwId = bgpNetworkId(rifPortId.asProto)

        createBgpPeer(30, rtrId, "20.0.0.1")

        val rifPort2Id = createNetworkAndRouterInterface(
            40, rtrId, "10.0.1.0/24", "10.0.1.1")
        val bgpNw2Id = bgpNetworkId(rifPort2Id.asProto)

        eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpNetworkIdsList should contain only(bgpNwId, bgpNw2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }

        insertDeleteTask(50, PortType, rifPortId)
        eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpNetworkIdsList should contain only bgpNw2Id
            checkNoBgpNetwork(rifPortId)
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }

        insertDeleteTask(60, PortType, rifPort2Id)
        eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpNetworkIdsCount shouldBe 0
            checkNoBgpNetwork(rifPort2Id)
        }
    }

    "BgpSpeakerTranslator" should "delete specified BgpPeers on update" in {
        val rtrId = createRouter(10)
        createNetworkAndRouterInterface(20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val peerId = createBgpPeer(30, rtrId, "30.0.0.1")
        val peer2Id = createBgpPeer(40, rtrId, "40.0.0.1")
        val peer3Id = createBgpPeer(50, rtrId, "50.0.0.1")
        val peer4Id = createBgpPeer(60, rtrId, "60.0.0.1")
        eventually {
            Seq(peerId, peer2Id, peer3Id, peer4Id)
                .map(storage.exists(classOf[BgpPeer], _))
                .map(_.await()) shouldBe Seq(true, true, true, true)
        }

        val speakerId = UUID.randomUUID()
        val speakerJson = bgpSpeakerJson(
            rtrId, id = speakerId, delBgpPeerIds = Seq(peerId, peer3Id))
        insertUpdateTask(70, BgpSpeakerType, speakerJson, speakerId)
        eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpPeerIdsList should contain only(
                peer2Id.asProto, peer4Id.asProto)

            checkNoBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
            checkNoBgpPeer(rtrId, peer3Id)
            checkBgpPeer(rtrId, peer2Id)
        }

        val speakerJson2 = bgpSpeakerJson(
            rtrId, id = speakerId, delBgpPeerIds = Seq(peer2Id, peer4Id))
        insertUpdateTask(80, BgpSpeakerType, speakerJson2, speakerId)
        eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.getBgpPeerIdsCount shouldBe 0

            checkNoBgpPeer(rtrId, peer2Id)
            checkNoBgpPeer(rtrId, peer2Id)
            checkQuaggaContainer(rtrId)
        }
    }

    "RouterTranslator" should "delete a router's container, container group, " +
                              "group, container port, and BGP networks when " +
                              "deleting that router" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val rifPort2Id = createNetworkAndRouterInterface(
            30, rtrId, "10.0.1.0/24", "10.0.1.1")
        val peerId = createBgpPeer(40, rtrId, "30.0.0.1")
        val peer2Id = createBgpPeer(50, rtrId, "40.0.0.1")
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkBgpNetwork(rtrId, rifPort2Id, "10.0.1.0/24")
        }

        insertDeleteTask(60, BgpPeerType, peerId)
        insertDeleteTask(70, BgpPeerType, peer2Id)
        insertDeleteTask(80, RouterType, rtrId)
        eventually {
            checkNoBgpPeer(rtrId, peerId)
            checkNoBgpPeer(rtrId, peer2Id)
            checkNoQuaggaContainer(rtrId)
            checkNoBgpNetwork(rifPortId)
            checkNoBgpNetwork(rifPort2Id)
        }
    }

    "RouterTranslator" should "update bgp networks on the router according" +
                              "to the extra routes that exist on the router" in {
        val sub1 = "10.0.0.0/24"
        val rId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rId, sub1, "10.0.0.1")

        createBgpPeer(30, rId, "10.0.0.2")

        eventually {
            checkBgpNetwork(rId, rifPortId, sub1)
        }

        val route1 = makeRoute("10.0.2.0/24", "10.0.0.3")
        val route2 = makeRoute("10.0.1.0/24", "10.0.0.3")

        val rtrWithRoutes = routerJson(rId, routes = List(route1, route2))

        insertUpdateTask(40, RouterType, rtrWithRoutes, rId)

        eventually {
            checkRoutesAndNetworks(rId, 2)
        }

        val nr = storage.get(classOf[NeutronRouter], rId).await()
        val routes = nr.getRoutesList.asScala

        insertUpdateTask(50, RouterType, routerJson(rId), rId)

        eventually {
            checkRoutesAndNetworks(rId, 0, Some(routes), false)
        }
    }

    "RouterTranslator" should "update bgp networks on the router according " +
                              "to the extra routes that exist on the router" +
                              " triggered by bgp peer creation/deletion " in {

        val sub1 = "10.0.0.0/24"
        val rId = createRouter(10)
        createNetworkAndRouterInterface(20, rId, sub1, "10.0.0.1")

        val route1 = makeRoute("10.0.2.0/24", "10.0.0.3")
        val route2 = makeRoute("10.0.1.0/24", "10.0.0.3")
        val rtrWithRoutes = routerJson(rId, routes = List(route1, route2))

        insertUpdateTask(30, RouterType, rtrWithRoutes, rId)

        val peerId = createBgpPeer(40, rId, "10.0.0.2")

        eventually {
            checkRoutesAndNetworks(rId, 2)
        }

        val nr = storage.get(classOf[NeutronRouter], rId).await()
        val routes = nr.getRoutesList.asScala

        insertDeleteTask(50, BgpPeerType, peerId)

        eventually {
            checkRoutesAndNetworks(rId, 2, Some(routes), false)
        }
    }

    "BgpPeerTranslator" should "not add external networks to " +
                               "the list of peer networks" in {
        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val rifPort2Id = createNetworkAndRouterInterface(
            30, rtrId, "10.0.1.0/24", "10.0.1.1", external = true)
        val peerId = createBgpPeer(40, rtrId, "30.0.0.1")
        val peer2Id = createBgpPeer(50, rtrId, "40.0.0.1")
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkNoBgpNetwork(rifPort2Id)
        }
    }

    "BgpPeerTranslator" should "not add external networks to " +
                               "the list of peer networks " +
                               "after the peer has been created" in {

        val rtrId = createRouter(10)
        val rifPortId = createNetworkAndRouterInterface(
            20, rtrId, "10.0.0.0/24", "10.0.0.1")
        val peerId = createBgpPeer(40, rtrId, "30.0.0.1")
        val peer2Id = createBgpPeer(50, rtrId, "40.0.0.1")
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
        }
        val rifPort2Id = createNetworkAndRouterInterface(
            30, rtrId, "10.0.1.0/24", "10.0.1.1", external = true)
        eventually {
            checkQuaggaContainer(rtrId)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peer2Id)
            checkBgpNetwork(rtrId, rifPortId, "10.0.0.0/24")
            checkNoBgpNetwork(rifPort2Id)
        }
    }

    "BgpPeerTranslator" should "not clear AS number if there are" +
                               " multiple peers" in {
        val rtrId = createRouter(10)
        val peerId = createBgpPeer(20, rtrId, "30.0.0.1",
                                   speakerLocalAs = 40000)
        val peerId2 = createBgpPeer(30, rtrId, "40.0.0.1",
                                    speakerLocalAs = 40000)

        eventually {
            checkRouterASnumber(rtrId, 40000)
            checkBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peerId2)
        }

        insertDeleteTask(40, BgpPeerType, peerId)

        eventually {
            checkRouterASnumber(rtrId, 40000)
            checkNoBgpPeer(rtrId, peerId)
            checkBgpPeer(rtrId, peerId2)
        }
    }

    "BgpPeerTranslator" should "clear AS number if there are" +
                               " no more peers" in {
        val rtrId = createRouter(10)
        val peerId = createBgpPeer(20, rtrId, "30.0.0.1",
            speakerLocalAs = 40000)

        eventually {
            checkRouterASnumber(rtrId, 40000)
            checkBgpPeer(rtrId, peerId)
        }

        insertDeleteTask(40, BgpPeerType, peerId)

        eventually {
            checkRouterASnumber(rtrId)
            checkNoBgpPeer(rtrId, peerId)
        }
    }

    "BgpPeerTranslator" should "change AS number" in {
        val rtrId = createRouter(10)
        val peerId = createBgpPeer(20, rtrId, "30.0.0.1",
                                   speakerLocalAs = 40000)

        eventually {
            checkRouterASnumber(rtrId, 40000)
            checkBgpPeer(rtrId, peerId)
        }

        insertDeleteTask(30, BgpPeerType, peerId)

        eventually {
            checkRouterASnumber(rtrId, -1)
        }

        val peerId2 = createBgpPeer(40, rtrId, "30.0.0.1",
                                    speakerLocalAs = 50000)

        eventually {
            checkRouterASnumber(rtrId, 50000)
            checkBgpPeer(rtrId, peerId2)
        }
    }

    def checkRouterASnumber(rId: UUID, as: Int = -1): Unit = {
        val router = storage.get(classOf[Router], rId).await()
        as match {
            case -1 => router.hasAsNumber shouldBe false
            case _ => router.getAsNumber shouldBe as
        }
    }

    def checkRoutesAndNetworks(rId: UUID, numRoutes: Int,
                               oldRts: Option[Iterable[NeutronRoute]] = None,
                               exists: Boolean = true)
    : Unit = {
        val nr = storage.get(classOf[NeutronRouter], rId).await()
        val nroutes = nr.getRoutesList.asScala
        nroutes.size shouldBe numRoutes
        val routes = oldRts.getOrElse(nroutes)
        routes foreach (checkBgpNetwork(rId, _, exists))
    }

    private def makeRoute(sub: String, gw: String) = {
        NeutronRoute.newBuilder()
          .setDestination(IPSubnetUtil.toProto(sub))
          .setNexthop(IPAddressUtil.toProto(gw)).build
    }

    private def createNetworkAndRouterInterface(firstTaskId: Int, rtrId: UUID,
                                                cidr: String, ipAddr: String,
                                                external: Boolean = false)
    : UUID = {
        val nwId = createTenantNetwork(firstTaskId, external = external)
        val snId = createSubnet(firstTaskId + 1, nwId, cidr)
        val rifPortId = createRouterInterfacePort(
            firstTaskId + 2, nwId, snId, rtrId, ipAddr)
        createRouterInterface(firstTaskId + 4, rtrId, rifPortId, snId)
        rifPortId
    }

    private def checkQuaggaContainer(routerId: UUID): Unit = {
        val port = storage.get(classOf[Port],
                               quaggaPortId(routerId.asProto)).await()
        port.getRouterId.asJava shouldBe routerId

        val groupId = quaggaContainerGroupId(routerId.asProto)
        val containerId = quaggaContainerId(routerId.asProto)

        val group = storage.get(classOf[ServiceContainerGroup],groupId).await()
        group.getServiceContainerIdsList should contain(containerId)

        val container = storage.get(classOf[ServiceContainer],
                                    quaggaContainerId(routerId.asProto)).await()
        container.getServiceGroupId shouldBe groupId
        container.getPortId shouldBe port.getId
        container.getServiceType shouldBe "QUAGGA"
        container.getConfigurationId.asJava shouldBe routerId
    }

    private def checkNoQuaggaContainer(routerId: UUID): Unit = {
        val scgId = quaggaContainerGroupId(routerId.asProto)
        storage.exists(classOf[ServiceContainerGroup], scgId)
            .await() shouldBe false

        val scId = quaggaContainerId(routerId.asProto)
        storage.exists(classOf[ServiceContainer], scId).await() shouldBe false
    }

    private def checkNoBgpPeer(routerId: UUID, bgpPeerId: UUID): Unit = {
        Seq(storage.exists(classOf[NeutronBgpPeer], bgpPeerId),
            storage.exists(classOf[BgpPeer], bgpPeerId),
            storage.exists(classOf[Rule], redirectRuleId(bgpPeerId.asProto)),
            storage.exists(classOf[Rule],
                           inverseRedirectRuleId(bgpPeerId.asProto)))
            .map(_.await()) shouldBe Seq(false, false, false, false)
    }

    private def checkBgpPeer(routerId: UUID, bgpPeerId: UUID): Unit = {
        val nBgpPeer = storage.get(classOf[NeutronBgpPeer], bgpPeerId).await()
        val mBgpPeer = storage.get(classOf[BgpPeer], bgpPeerId).await()

        mBgpPeer.getRouterId shouldBe routerId.asProto
        mBgpPeer.getAddress shouldBe nBgpPeer.getPeerIp
        mBgpPeer.getAsNumber shouldBe nBgpPeer.getRemoteAs

        val router = storage.get(classOf[Router], routerId).await()
        router.hasLocalRedirectChainId shouldBe true
        router.getAsNumber shouldBe nBgpPeer.getBgpSpeaker.getLocalAs

        val chain = storage.get(classOf[Chain],
                                router.getLocalRedirectChainId).await()
        checkPeerRedirectRule(mBgpPeer, routerId, chain, inverse = false)
        checkPeerRedirectRule(mBgpPeer, routerId, chain, inverse = true)
    }

    private def checkPeerRedirectRule(bgpPeer: BgpPeer, routerId: UUID,
                                      chain: Chain, inverse: Boolean): Unit = {
        val ruleId = if (inverse) {
            inverseRedirectRuleId(bgpPeer.getId)
        } else {
            redirectRuleId(bgpPeer.getId)
        }
        chain.getRuleIdsList should contain(ruleId)
        val rule = storage.get(classOf[Rule], ruleId).await()

        val ruleData = rule.getTransformRuleData
        ruleData.getTargetPortId shouldBe quaggaPortId(routerId.asProto)

        val cond = rule.getCondition
        cond.getNwSrcIp.getAddress shouldBe bgpPeer.getAddress.getAddress
        cond.getNwProto shouldBe TCP.PROTOCOL_NUMBER
        if (inverse) {
            cond.getTpSrc shouldBe BgpPortRange
        } else {
            cond.getTpDst shouldBe BgpPortRange
        }
    }

    private def checkBgpNetwork(rId: UUID, route: NeutronRoute,
                                exists: Boolean = true): Unit = {
        val erId = bgpNetworkId(extraRouteId(UUIDUtil.toProto(rId), route))
        storage.exists(classOf[BgpNetwork], erId).await() shouldBe exists
    }

    private def checkBgpNetwork(rtrId: UUID, rifPortId: UUID,
                                cidr: String): Unit = {
        val bgpNw = storage.get(classOf[BgpNetwork],
                                bgpNetworkId(rifPortId.asProto)).await()
        bgpNw.getRouterId.asJava shouldBe rtrId
        bgpNw.getSubnet.asJava shouldBe IPv4Subnet.fromCidr(cidr)
    }

    private def checkNoBgpNetwork(rifPortId: UUID): Unit = {
        storage.exists(classOf[BgpNetwork],
                       bgpNetworkId(rifPortId.asProto)).await() shouldBe false
    }
}
