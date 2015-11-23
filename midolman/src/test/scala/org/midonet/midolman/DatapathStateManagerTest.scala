/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman

import java.util.UUID

import scala.util.Random

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.midonet.cluster.data.TunnelZone.{Type => TunnelType}
import org.midonet.midolman.topology.devices.TunnelZoneType
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.odp.ports.{GreTunnelPort, VxLanTunnelPort}
import org.midonet.sdn.flows.FlowTagger
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, Matchers, Suite}
import org.slf4j.helpers.NOPLogger

@RunWith(classOf[JUnitRunner])
class DatapathStateManagerTest extends Suite with Matchers with BeforeAndAfter {

    import org.midonet.midolman.UnderlayResolver.Route

    type MaybePort = Option[DpPort]

    val r = new Random
    val peers = List.fill(100) { UUID.randomUUID }.toSet.toList
    val zones = List.fill(10) { UUID.randomUUID }.toSet.toList
    val myIps = List.fill(1000) { r.nextInt }
    val peerIps = List.fill(1000) { r.nextInt }
    val ipPairs = (myIps zip peerIps).toSet.toList

    val gre = TunnelZoneType.GRE
    val vxlan = TunnelZoneType.VXLAN

    implicit val log = Logger(NOPLogger.NOP_LOGGER)

    /* class reference tests work with */
    var stateMgr: DatapathStateDriver = null

    before {
        stateMgr = new DatapathStateDriver(new Datapath(0, "midonet"))
        stateMgr.tunnelOverlayGre shouldBe null
        stateMgr.tunnelOverlayVxLan shouldBe null
        stateMgr.tunnelVtepVxLan shouldBe null
        stateMgr.tunnelOverlayGre shouldBe null
        stateMgr.tunnelOverlayVxLan shouldBe null
        stateMgr.tunnelVtepVxLan shouldBe null
        for (p <- peers) { (stateMgr peerTunnelInfo p) shouldBe None }
    }

    def testAddRemoveOnePeer {
        val port = DpPort fakeFrom (GreTunnelPort make "bla", 1)
        val output = port.toOutputAction
        stateMgr.tunnelOverlayGre = port.asInstanceOf[GreTunnelPort]
        (1 to 100) foreach { _ => {
            val r = Random
            val peer = peers(r nextInt peers.length)
            val zone = zones(r nextInt zones.length)
            val (src,dst) = ipPairs(r nextInt ipPairs.length)
            val tag = FlowTagger tagForTunnelRoute (src,dst)

            stateMgr.peerTunnelInfo(peer) shouldBe None
            stateMgr.addPeer(peer, zone, src, dst, gre) should contain(tag)
            stateMgr.peerTunnelInfo(peer) shouldBe Some(Route(src,dst,output))
            stateMgr.removePeer(peer, zone) shouldBe Some(tag)
            stateMgr.peerTunnelInfo(peer) shouldBe None
            // remove all routes before next iteration
        } }
    }

    def testMultipleZonesOnePeer {
        val port1 = DpPort fakeFrom (GreTunnelPort make "bla", 1)
        val port2 = DpPort fakeFrom (VxLanTunnelPort make "meh", 2)
        val output1 = port1.toOutputAction
        val output2 = port2.toOutputAction
        stateMgr.tunnelOverlayGre = port1.asInstanceOf[GreTunnelPort]
        stateMgr.tunnelOverlayVxLan = port2.asInstanceOf[VxLanTunnelPort]
        (1 to 100) foreach { _ => {
            val r = Random
            val peer = peers(r nextInt peers.length)
            val zoneI = r nextInt zones.length
            val zone1 = zones(zoneI)
            val zone2 = zones((zoneI+1)%zones.length)
            val routeI = r nextInt ipPairs.length
            val (src1, dst1) = ipPairs(routeI)
            val (src2, dst2) = ipPairs((routeI+1)%ipPairs.length)
            val route1 = Route(src1, dst1, output1)
            val route2 = Route(src2, dst2, output2)
            val tag1 = FlowTagger tagForTunnelRoute (src1, dst1)
            val tag2 = FlowTagger tagForTunnelRoute (src2, dst2)

            stateMgr.peerTunnelInfo(peer) shouldBe None
            stateMgr.addPeer(peer, zone1, src1, dst1, gre) should contain(tag1)
            stateMgr.peerTunnelInfo(peer) shouldBe Some(route1)

            stateMgr.addPeer(peer, zone2, src2, dst2, vxlan) should contain(tag2)

            stateMgr.removePeer(peer, zone1) shouldBe Some(tag1)
            stateMgr.peerTunnelInfo(peer) shouldBe Some(route2)
            stateMgr.removePeer(peer, zone2) shouldBe Some(tag2)
            stateMgr.peerTunnelInfo(peer) shouldBe None
            // remove all routes before next iteration
        } }
    }

    def testMultipleZonesMultiplePeer {
        val port1 = DpPort fakeFrom (GreTunnelPort make "bla", 1)
        val port2 = DpPort fakeFrom (VxLanTunnelPort make "meh", 2)
        val output1 = port1.toOutputAction
        val output2 = port2.toOutputAction
        stateMgr.tunnelOverlayGre = port1.asInstanceOf[GreTunnelPort]
        stateMgr.tunnelOverlayVxLan = port2.asInstanceOf[VxLanTunnelPort]
        (1 to 100) foreach { _ => {
            val r = Random
            val peerI = r nextInt peers.length
            val peer1 = peers(peerI)
            val peer2 = peers((peerI+1)%peers.length)
            val zoneI = r nextInt zones.length
            val zone1 = zones(zoneI)
            val zone2 = zones((zoneI+1)%zones.length)
            val routeI = r nextInt ipPairs.length
            val (src1, dst1) = ipPairs(routeI)
            val (src2, dst2) = ipPairs((routeI+1)%ipPairs.length)
            val route1 = Route(src1, dst1, output1)
            val route2 = Route(src2, dst2, output2)
            val tag1 = FlowTagger tagForTunnelRoute (src1, dst1)
            val tag2 = FlowTagger tagForTunnelRoute (src2, dst2)

            stateMgr.peerTunnelInfo(peer1) shouldBe None
            stateMgr.peerTunnelInfo(peer2) shouldBe None

            stateMgr.addPeer(peer1, zone1, src1, dst1, gre) should contain(tag1)
            stateMgr.peerTunnelInfo(peer1) shouldBe Some(route1)
            stateMgr.peerTunnelInfo(peer2) shouldBe None

            stateMgr.addPeer(peer2, zone2, src2, dst2, vxlan) should contain(tag2)
            stateMgr.peerTunnelInfo(peer1) shouldBe Some(route1)
            stateMgr.peerTunnelInfo(peer2) shouldBe Some(route2)

            stateMgr.removePeer(peer1, zone1) shouldBe Some(tag1)
            stateMgr.removePeer(peer2, zone2) shouldBe Some(tag2)
            stateMgr.peerTunnelInfo(peer1) shouldBe None
            stateMgr.peerTunnelInfo(peer2) shouldBe None
            // remove all routes before next iteration
        } }
    }

    def testAddMultipleRoutesToPeer1 {
        val port = DpPort fakeFrom (GreTunnelPort make "bla", 1)
        val output = port.toOutputAction
        stateMgr.tunnelOverlayGre = port.asInstanceOf[GreTunnelPort]
        (1 to 100) foreach { _ => {
            val r = Random
            val peer = peers(r nextInt peers.length)
            val zone = zones(r nextInt zones.length)

            val routes =
                List.fill(10) {  ipPairs(r nextInt ipPairs.length) }.toSet

            stateMgr.peerTunnelInfo(peer) shouldBe None

            var lastTag: Any = null
            var firstRoute = true
            for ( (src, dst) <- routes ) {
                val tags = stateMgr addPeer (peer, zone, src, dst, gre)

                // check tag overwrite
                if (!firstRoute) {
                    tags should have length 2
                    tags(0) shouldBe lastTag
                }
                lastTag = tags.last

                stateMgr.peerTunnelInfo(peer) shouldBe Some(Route(src, dst, output))
                firstRoute = false
            }

            stateMgr.removePeer(peer, zone)
            stateMgr.peerTunnelInfo(peer) shouldBe None
            // remove all routes before next iteration
        } }
    }

    def testAddMultipleRoutesToPeer2 {
        val port = DpPort fakeFrom (GreTunnelPort make "bla", 1)
        val output = port.toOutputAction
        stateMgr.tunnelOverlayGre = port.asInstanceOf[GreTunnelPort]
        (1 to 100) foreach { _ => {
            val r = Random
            val peer = peers(r.nextInt(peers.length))

            val routes = List.fill(10) { ipPairs(r nextInt ipPairs.length) }
            val inZones = List.fill(10) { zones(r nextInt zones.length) }
            val zoneIPs = routes.toSet zip inZones.toSet

            var added = Set[Route]()

            stateMgr.peerTunnelInfo(peer) shouldBe None

            for ( ((src,dst),zone) <- zoneIPs ) {
                stateMgr.addPeer(peer, zone, src, dst, gre)
                added += Route(src, dst, output)
                val found = stateMgr peerTunnelInfo peer
                found should not be (None)
                added should contain(found.get)
            }

            for ( ((src,dst),zone) <- Random.shuffle(zoneIPs) ) {
                stateMgr.removePeer(peer, zone)
                added -= Route(src, dst, output)
                val found = stateMgr peerTunnelInfo peer
                found.isEmpty shouldBe added.isEmpty
            }
            stateMgr.peerTunnelInfo(peer) shouldBe None
            // remove all routes before next iteration
        } }
    }
}
