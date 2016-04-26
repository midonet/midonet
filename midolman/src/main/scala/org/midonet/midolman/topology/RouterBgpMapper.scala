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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.breakOut

import com.google.protobuf.GeneratedMessage

import rx.Observable

import org.midonet.cluster.models.Topology.{BgpPeer, Port, Router}
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.{makeAction0, makeFunc1}

/** Given a router ID, produces an observable that publishes the CIDR, MAC, port
  * ID, BGP peer IP, and  for all of the router's ports that have a BGP peer.
  */
class RouterBgpMapper(id: UUID, vt: VirtualTopology) extends MidolmanLogging {

    override def logSource: String =
        "org.midonet.routing.bgp.router-ip-mapper-" + id

    private var portIds: Set[UUID] = _
    private val portTracker = new StoreObjectReferenceTracker[Port](vt, log)

    private var bgpIds: Set[UUID] = _
    private val bgpTracker = new StoreObjectReferenceTracker[BgpPeer](vt, log)

    private val routerObservable = vt.store.observable(classOf[Router], id)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(routerDeleted()))
        .filter(makeFunc1(routerUpdated))

    val portBgpInfoObservable = Observable
        .merge(portTracker.refsObservable,
               bgpTracker.refsObservable,
               routerObservable)
        .observeOn(vt.vtScheduler)
        .filter(makeFunc1(isReady))
        .map[Seq[PortBgpInfo]](makeFunc1(getPortBgpInfos))
        .distinctUntilChanged()

    private def routerUpdated(r: Router): Boolean = {
        val newPortIds: Set[UUID] = r.getPortIdsList.map(_.asJava)(breakOut)
        val newBgpIds: Set[UUID] = r.getBgpPeerIdsList.map(_.asJava)(breakOut)
        if (newBgpIds == bgpIds && newPortIds == portIds)
            return false // No relevant changes.

        if (newPortIds != portIds) {
            portIds = newPortIds
            portTracker.requestRefs(portIds)
        }

        if (newBgpIds != bgpIds) {
            bgpIds = newBgpIds
            bgpTracker.requestRefs(bgpIds)
        }

        true
    }

    private def routerDeleted(): Unit = {
        portTracker.completeRefs()
        bgpTracker.completeRefs()
    }

    private def isReady(gm: GeneratedMessage): Boolean = {
        portIds != null && portTracker.areRefsReady && bgpTracker.areRefsReady
    }

    private def getPortBgpInfos(gm: GeneratedMessage): Seq[PortBgpInfo] = {
        // Get map of all BGP peers by IP address.
        val ipToBgpPeer = bgpTracker.currentRefs.values.map {
            bgp => toIPv4Addr(bgp.getAddress) -> bgp
        }

        // Return true if the port's subnet contains any of the BGP peers' IPs.
        def getBgpPeer(p: Port): Option[BgpPeer] = {
            val subnet = IPSubnetUtil.fromProto(p.getPortSubnet)
            val bgpPeer = ipToBgpPeer.find {
                case (ip, peer) => subnet.containsAddress(ip)
            }.map(_._2)
            if (bgpPeer.isDefined) {
                log.debug("Port with subnet {} has BGP peer {}",
                          subnet, bgpPeer.get.getId.asJava)
            }
            bgpPeer
        }

        // Return a set containing the IP address and subnet prefix length of
        // any port whose subnet contains the IP address of a BGP peer.
        for (p <- portTracker.currentRefs.values.toSeq;
             bgpPeer <- getBgpPeer(p)) yield {
            val addr = p.getPortAddress.getAddress
            val prefixLen = p.getPortSubnet.getPrefixLength
            PortBgpInfo(p.getId.asJava, p.getPortMac, s"$addr/$prefixLen",
                        bgpPeer.getAddress.getAddress)
        }
    }

}

case class PortBgpInfo(portId: UUID, mac: String, cidr: String,
                       bgpPeerIp: String)

