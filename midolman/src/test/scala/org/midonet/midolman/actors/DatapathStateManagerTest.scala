/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman

import java.util.UUID
import scala.util.Random

import akka.actor.ActorContext
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, Matchers, Suite}
import org.scalatest.junit.JUnitRunner

import org.midonet.odp.Port
import org.midonet.odp.ports.GreTunnelPort
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.topology.FlowTagger

@RunWith(classOf[JUnitRunner])
class DatapathStateManagerTest extends Suite with Matchers with BeforeAndAfter {

    type MaybePort = Option[Port[_,_]]

    val r = new Random
    val peers = List.tabulate(100){ _ => UUID.randomUUID }.toSet.toList
    val zones = List.tabulate(10){ _ => UUID.randomUUID }.toSet.toList
    val myIps = List.tabulate(1000){ _ => r.nextInt}
    val peerIps = List.tabulate(1000){ _ => r.nextInt}
    val ipPairs = myIps.zip(peerIps).toSet.toList

    implicit val log = SoloLogger(classOf[DatapathStateManagerTest])
    log.isInfoEnabled = false
    log.isDebugEnabled = false

    implicit val context: ActorContext = null

    var controller = new VirtualPortManager.Controller {
        override def addToDatapath(itfName: String) = { }
        override def removeFromDatapath(port: Port[_,_]) = { }
        override def setVportStatus(
            port: Port[_,_], vportId: UUID, isActive: Boolean) = { }
    }

    /* class reference tests work with */
    var stateMgr: DatapathStateManager = null

    before {
        stateMgr = new DatapathStateManager(controller)(null, log)
        stateMgr.version should be (0)
        stateMgr.tunnelGre should be (None)
        stateMgr.host should be (null)
        for (p <- peers) { stateMgr.peerTunnelInfo(p) should be (None) }
    }

    after { }

    def checkVUp(testToRun: => Unit) {
        val ver = stateMgr.version
        testToRun
        stateMgr.version should be > (ver)
    }

    def testTunnelSet {
        List[MaybePort](
            Some(null),
            None,
            Some(GreTunnelPort.make("foo")),
            None,
            Some(GreTunnelPort.make("bar"))
        ).foreach { tun => checkVUp {
                stateMgr.tunnelGre = tun
                stateMgr.tunnelGre should be (tun)
            }
        }
    }

    def testHostSet {
        List[Host](
            null
            /* add some more */
        ).foreach { h => checkVUp {
                stateMgr.host = h
                stateMgr.host should be (h)
            }
        }
    }

    def testAddRemoveOnePeer {
        (1 to 100) foreach { _ => checkVUp {
            val r = Random
            val peer = peers(r.nextInt(peers.length))
            val zone = zones(r.nextInt(zones.length))
            val route = ipPairs(r.nextInt(ipPairs.length))
            val tag = FlowTagger.invalidateTunnelPort(route)

            stateMgr.peerTunnelInfo(peer) should be (None)
            stateMgr.addPeer(peer, zone, route).contains(tag) should be (true)
            stateMgr.peerTunnelInfo(peer) should be (Some(route))
            stateMgr.removePeer(peer, zone) should be (Some(tag))
            stateMgr.peerTunnelInfo(peer) should be (None)
            // remove all routes before next iteration
        } }
    }

    def testMultipleZonesOnePeer {
        (1 to 100) foreach { _ => checkVUp {
            val r = Random
            val peer = peers(r.nextInt(peers.length))
            val zoneI = r.nextInt(zones.length)
            val zone1 = zones(zoneI)
            val zone2 = zones((zoneI+1)%zones.length)
            val routeI = r.nextInt(ipPairs.length)
            val route1 = ipPairs(routeI)
            val route2 = ipPairs((routeI+1)%ipPairs.length)
            val tag1 = FlowTagger.invalidateTunnelPort(route1)
            val tag2 = FlowTagger.invalidateTunnelPort(route2)

            stateMgr.peerTunnelInfo(peer) should be (None)
            stateMgr.addPeer(peer, zone1, route1).contains(tag1) should be (true)
            stateMgr.peerTunnelInfo(peer) should be (Some(route1))

            stateMgr.addPeer(peer, zone2, route2).contains(tag2) should be (true)
            Set(None, Some(route1), Some(route2))
                .contains((stateMgr.peerTunnelInfo(peer))) should be (true)

            stateMgr.removePeer(peer, zone1) should be (Some(tag1))
            stateMgr.peerTunnelInfo(peer) should be (Some(route2))
            stateMgr.removePeer(peer, zone2) should be (Some(tag2))
            stateMgr.peerTunnelInfo(peer) should be (None)
            // remove all routes before next iteration
        } }
    }

    def testMultipleZonesMultiplePeer {
        (1 to 100) foreach { _ => checkVUp {
            val r = Random
            val peerI = r.nextInt(peers.length)
            val peer1 = peers(peerI)
            val peer2 = peers((peerI+1)%peers.length)
            val zoneI = r.nextInt(zones.length)
            val zone1 = zones(zoneI)
            val zone2 = zones((zoneI+1)%zones.length)
            val routeI = r.nextInt(ipPairs.length)
            val route1 = ipPairs(routeI)
            val route2 = ipPairs((routeI+1)%ipPairs.length)
            val tag1 = FlowTagger.invalidateTunnelPort(route1)
            val tag2 = FlowTagger.invalidateTunnelPort(route2)

            stateMgr.peerTunnelInfo(peer1) should be (None)
            stateMgr.peerTunnelInfo(peer2) should be (None)

            stateMgr.addPeer(peer1, zone1, route1).contains(tag1) should be (true)
            stateMgr.peerTunnelInfo(peer1) should be (Some(route1))
            stateMgr.peerTunnelInfo(peer2) should be (None)

            stateMgr.addPeer(peer2, zone2, route2).contains(tag2) should be (true)
            stateMgr.peerTunnelInfo(peer1) should be (Some(route1))
            stateMgr.peerTunnelInfo(peer2) should be (Some(route2))

            stateMgr.removePeer(peer1, zone1) should be (Some(tag1))
            stateMgr.removePeer(peer2, zone2) should be (Some(tag2))
            stateMgr.peerTunnelInfo(peer1) should be (None)
            stateMgr.peerTunnelInfo(peer2) should be (None)
            // remove all routes before next iteration
        } }
    }

    def testAddMultipleRoutesToPeer1 {
        (1 to 100) foreach { _ => checkVUp {
            val r = Random
            val peer = peers(r.nextInt(peers.length))
            val zone = zones(r.nextInt(zones.length))

            val routes = List
                .tabulate(10){_ => ipPairs(r.nextInt(ipPairs.length))}
                .toSet

            stateMgr.peerTunnelInfo(peer) should be (None)

            var lastTag: Any = null
            var firstRoute = true
            for ( route <- routes ) {
                val tags = stateMgr.addPeer(peer, zone, route)

                // check tag overwrite
                if (!firstRoute) {
                    tags.length should be (2)
                    tags(0) should be (lastTag)
                }
                lastTag = tags.last

                val found = stateMgr.peerTunnelInfo(peer)
                found should be (Some(route))
                firstRoute = false
            }

            stateMgr.removePeer(peer, zone)
            stateMgr.peerTunnelInfo(peer) should be (None)
            // remove all routes before next iteration
        } }
    }

    def testAddMultipleRoutesToPeer2 {
        (1 to 100) foreach { _ => checkVUp {
            val r = Random
            val peer = peers(r.nextInt(peers.length))

            val routes = List.tabulate(10){ _ => ipPairs(r.nextInt(ipPairs.length)) }
            val inZones = List.tabulate(10){ _ => zones(r.nextInt(zones.length)) }
            val zoneIPs = routes.toSet.zip(inZones.toSet)

            var added = Set[(Int,Int)]()

            stateMgr.peerTunnelInfo(peer) should be (None)

            for ( (route,zone) <- zoneIPs ) {
                stateMgr.addPeer(peer, zone, route)
                added += route
                val found = stateMgr.peerTunnelInfo(peer)
                found should not be (None)
                added.contains(found.get) should be (true)
            }

            for ( (route,zone) <- Random.shuffle(zoneIPs) ) {
                stateMgr.removePeer(peer, zone)
                added -= route
                val found = stateMgr.peerTunnelInfo(peer)
                found.isEmpty should be (added.isEmpty)
            }
            stateMgr.peerTunnelInfo(peer) should be (None)
            // remove all routes before next iteration
        } }
    }

}
