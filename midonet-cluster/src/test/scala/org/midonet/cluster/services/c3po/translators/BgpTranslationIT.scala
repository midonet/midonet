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

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{BgpPeer => BgpPeerType, BgpSpeaker => BgpSpeakerType, Port => PortType}
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronBgpSpeaker}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class BgpTranslationIT extends C3POMinionTestBase {

    import BgpSpeakerTranslator._
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "BgpSpeakerTranslator" should "create associated objects" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)

        createRouterInterfacePort(30, netId, subId, routerId, "10.0.0.1", MAC.random().toString)

        val speakerId = createBgpSpeaker(40, routerId = routerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr)

            storage.exists(classOf[NeutronBgpSpeaker], speakerId).await() should be (true)
            storage.exists(classOf[Port], quaggaPortId(routerId)).await() should be (true)
            storage.exists(classOf[ServiceContainer], quaggaContainerId(routerId)).await() should be (true)
        }
    }

    "BgpSpeakerTranslator" should "delete associated objects" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)

        createRouterInterfacePort(30, netId, subId, routerId, "10.0.0.1", MAC.random().toString)

        val speakerId = createBgpSpeaker(40, routerId = routerId)
        insertDeleteTask(50, BgpSpeakerType, speakerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.size should be (0)

            storage.exists(classOf[NeutronBgpSpeaker], speakerId).await() should be (false)
            storage.exists(classOf[Port], quaggaPortId(routerId)).await() should be (false)
            storage.exists(classOf[ServiceContainer], quaggaContainerId(routerId)).await() should be (false)
        }
    }

    "BgpPeerTranslator" should "create associated objects" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)

        val speakerId = createBgpSpeaker(40, routerId = routerId)
        val peerId = createBgpPeer(50, peerIp = "10.0.0.4", speakerId = speakerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            r.getBgpPeerIdsList.map(id => fromProto(id)) shouldBe Seq(peerId)

            storage.exists(classOf[NeutronBgpPeer], peerId).await() shouldBe true
            storage.exists(classOf[BgpPeer], peerId).await() shouldBe true

            val chain = storage.get(classOf[Chain], r.getLocalRedirectChainId).await()
            val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList).await()
            rules.map(_.getCondition.getNwSrcIp.getAddress) shouldBe Seq("10.0.0.4")
        }
    }

    "BgpPeerTranslator" should "delete associated objects" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)

        val speakerId = createBgpSpeaker(40, routerId = routerId)

        val peerId = createBgpPeer(50, peerIp = "10.0.0.4", speakerId = speakerId)
        insertDeleteTask(51, BgpPeerType, peerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            r.getBgpPeerIdsCount shouldBe 0

            storage.exists(classOf[NeutronBgpPeer], peerId).await() shouldBe false
            storage.exists(classOf[BgpPeer], peerId).await() shouldBe false

            val chain = storage.get(classOf[Chain], r.getLocalRedirectChainId).await()
            val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList).await()
            rules.size should be (0)
        }
    }

    "RouterInterfaceTranslator" should "should create and delete associated bgp networks" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24
        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")

        val cidrAddr2 = "10.0.1.0"
        val prefixLen2 = 24
        val netId2 = createTenantNetwork(11)
        val subId2 = createSubnet(12, netId, s"$cidrAddr2/$prefixLen")

        val routerId = createRouter(20)

        val p1Id = createRouterInterfacePort(30, netId, subId, routerId,
                                             "10.0.0.1", MAC.random().toString)

        val speakerId = createBgpSpeaker(50, routerId = routerId)

        val p2Id = createRouterInterfacePort(60, netId2, subId2, routerId,
                                             "10.0.1.1", MAC.random().toString)

        val peerId = createBgpPeer(70, peerIp = "10.0.0.4", speakerId = speakerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr, cidrAddr2)
        }

        insertDeleteTask(80, PortType, p1Id)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr2)
        }

        val cidrAddr3 = "10.0.2.0"
        val prefixLen3 = 24
        val netId3 = createTenantNetwork(90)
        val subId3 = createSubnet(91, netId, s"$cidrAddr3/$prefixLen3")

        val p3Id = createRouterInterfacePort(92, netId3, subId3, routerId,
                                             "10.0.2.1", MAC.random().toString)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr2, cidrAddr3)
        }
    }

    "Bgp Translators" should "manage peers and networks" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24
        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")

        val cidrAddr2 = "10.0.1.0"
        val prefixLen2 = 24
        val netId2 = createTenantNetwork(11)
        val subId2 = createSubnet(12, netId, s"$cidrAddr2/$prefixLen")

        val routerId = createRouter(20)

        val p1Id = createRouterInterfacePort(30, netId, subId, routerId,
            "10.0.0.1", MAC.random().toString)

        val speakerId = createBgpSpeaker(50, routerId = routerId)

        val p2Id = createRouterInterfacePort(60, netId2, subId2, routerId,
            "10.0.1.1", MAC.random().toString)

        val peerId = createBgpPeer(70, peerIp = "10.0.0.4", speakerId = speakerId)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            r.hasLocalRedirectChainId should be (true)

            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr, cidrAddr2)

            storage.exists(classOf[NeutronBgpPeer], peerId).await() should be (true)
            storage.exists(classOf[BgpPeer], peerId).await() should be (true)

            val chain = storage.get(classOf[Chain], r.getLocalRedirectChainId).await()
            val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList).await()
            rules.map(_.getCondition.getNwSrcIp.getAddress) should contain theSameElementsAs Seq("10.0.0.4")

            storage.exists(classOf[NeutronBgpSpeaker], speakerId).await() should be (true)
            storage.exists(classOf[Port], quaggaPortId(routerId)).await() should be (true)
            storage.exists(classOf[ServiceContainer], quaggaContainerId(routerId)).await() should be (true)
        }

        val peerId2 = createBgpPeer(71, peerIp = "10.0.1.4", speakerId = speakerId)

        insertDeleteTask(80, PortType, p1Id)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            r.hasLocalRedirectChainId should be (true)

            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr2)

            storage.exists(classOf[NeutronBgpPeer], peerId).await() should be (true)
            storage.exists(classOf[BgpPeer], peerId).await() should be (true)
            storage.exists(classOf[NeutronBgpPeer], peerId2).await() should be (true)
            storage.exists(classOf[BgpPeer], peerId2).await() should be (true)

            val chain = storage.get(classOf[Chain], r.getLocalRedirectChainId).await()
            val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList).await()
            rules.map(_.getCondition.getNwSrcIp.getAddress) should contain theSameElementsAs Seq("10.0.0.4", "10.0.1.4")

            storage.exists(classOf[NeutronBgpSpeaker], speakerId).await() should be (true)
            storage.exists(classOf[Port], quaggaPortId(routerId)).await() should be (true)
            storage.exists(classOf[ServiceContainer], quaggaContainerId(routerId)).await() should be (true)
        }

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr2)
        }

        val cidrAddr3 = "10.0.2.0"
        val prefixLen3 = 24
        val netId3 = createTenantNetwork(90)
        val subId3 = createSubnet(91, netId, s"$cidrAddr3/$prefixLen3")

        val p3Id = createRouterInterfacePort(92, netId3, subId3, routerId,
            "10.0.2.1", MAC.random().toString)

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr2, cidrAddr3)
        }
    }
}
