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

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{BgpSpeaker => BgpSpeakerType, BgpPeer => BgpPeerType, GatewayDevice => GatewayDeviceType, L2GatewayConnection => L2ConnType, RemoteMacEntry => RemoteMacEntryType}
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronBgpSpeaker}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class BgpTranslationIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "BgpSpeakerTranslator" should "should create associated bgp networks" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)
        createRouterInterfacePort(30, netId, subId, routerId, "10.0.0.1", MAC.random().toString)

        val peerId = createBgpPeer(40, peerIp = "10.0.0.4")

        eventually {
            val bgpPeer = storage.get(classOf[NeutronBgpPeer], peerId).await()
            bgpPeer.getPeerIp.getAddress shouldBe "10.0.0.4"
        }

        val speakerId = createBgpSpeaker(50, routerId = routerId, peers = List(peerId))

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.size should be (1)
            bgpNets.head.getSubnet.getAddress should be (cidrAddr)
            bgpNets.head.getSubnet.getPrefixLength should be (prefixLen)
        }
    }

    "RouterInterfaceTranslator" should "should create associated bgp networks" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24

        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")
        val routerId = createRouter(20)
        createRouterInterfacePort(30, netId, subId, routerId, "10.0.0.1", MAC.random().toString)

        val peerId = createBgpPeer(40, peerIp = "10.0.0.4")

        eventually {
            val bgpPeer = storage.get(classOf[NeutronBgpPeer], peerId).await()
            bgpPeer.getPeerIp.getAddress shouldBe "10.0.0.4"
        }

        val speakerId = createBgpSpeaker(50, routerId = routerId, peers = List(peerId))

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.size should be (1)
            bgpNets.head.getSubnet.getAddress should be (cidrAddr)
            bgpNets.head.getSubnet.getPrefixLength should be (prefixLen)
        }

        val net2Id = createTenantNetwork(60)
        val sub2Id = createSubnet(70, netId, s"$cidrAddr/$prefixLen")
    }

    "BgpSpeakerTranslation" should "should clean up on deletion" in {
        val cidrAddr = "10.0.0.0"
        val prefixLen = 24
        val netId = createTenantNetwork(1)
        val subId = createSubnet(10, netId, s"$cidrAddr/$prefixLen")

        val cidrAddr2 = "10.0.1.0"
        val prefixLen2 = 24
        val netId2 = createTenantNetwork(11)
        val subId2 = createSubnet(12, netId, s"$cidrAddr2/$prefixLen")


        val routerId = createRouter(20)
        createRouterInterfacePort(30, netId, subId, routerId, "10.0.0.1", MAC.random().toString)
        createRouterInterfacePort(31, netId2, subId2, routerId, "10.0.1.1", MAC.random().toString)

        val ip1 = "10.0.0.4"
        val ip2 = "10.0.1.4"

        val peerId = createBgpPeer(40, peerIp = ip1)
        val peerId2 = createBgpPeer(41, peerIp = ip2)

        eventually {
            val bgpPeers = storage.getAll(classOf[NeutronBgpPeer]).await()
            bgpPeers.map(_.getPeerIp.getAddress) should contain theSameElementsAs Seq(ip1, ip2)
        }

        val speakerId = createBgpSpeaker(50, routerId = routerId, peers = List(peerId, peerId2))

        eventually {
            val r = storage.get(classOf[Router], routerId).await()
            val bgpNets = storage.getAll(classOf[BgpNetwork], r.getBgpNetworkIdsList).await()
            bgpNets.map(_.getSubnet.getAddress) should contain theSameElementsAs Seq(cidrAddr, cidrAddr2)
        }

        insertDeleteTask(51, BgpSpeakerType, speakerId)

        eventually {
            val bgpPeers = storage.getAll(classOf[BgpPeer]).await()
            bgpPeers.size should be (0)
            val bgpNetworks = storage.getAll(classOf[BgpNetwork]).await()
            bgpPeers.size should be (0)
            storage.exists(classOf[Port], BgpSpeakerTranslator.quaggaPortId(routerId)).await() should be (false)
        }
    }
}
