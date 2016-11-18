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

import org.midonet.cluster.models.Commons.IPSubnet
import org.midonet.cluster.models.Topology.{BgpPeer, Port, Router}
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.util.functors.{makeAction0, makeFunc1}

/** Given a router ID, produces an observable that publishes the CIDR, MAC, port
  * ID, BGP peer IP, and  for all of the router's ports that have a BGP peer.
  */
class RouterBgpMapper(routerId: UUID, vt: VirtualTopology) extends MidolmanLogging {

    override def logSource = "org.midonet.routing.bgp"
    override def logMark = s"router-ip:$routerId"

    private var portIds: Set[UUID] = _
    private val portTracker =
        new StoreObjectReferenceTracker(vt, classOf[Port], log)

    private var bgpIds: Set[UUID] = _
    private val bgpTracker =
        new StoreObjectReferenceTracker(vt, classOf[BgpPeer], log)

    private val routerObservable = vt.store.observable(classOf[Router], routerId)
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
        def getBgpPeer(subnet: IPSubnet): Option[BgpPeer] = {
            val s = IPSubnetUtil.fromProto(subnet)
            val bgpPeer = ipToBgpPeer.find {
                case (ip, peer) => s.containsAddress(ip)
            }.map(_._2)
            if (bgpPeer.isDefined) {
                log.debug("Port with subnet {} has BGP peer {}",
                          subnet, bgpPeer.get.getId.asJava)
            }
            bgpPeer
        }

        // Return a set containing the IP address and subnet prefix length of
        // any port whose subnet contains the IP address of a BGP peer.
        for (port <- portTracker.currentRefs.values.toSeq;
             subnet <- port.getPortSubnetList;
             bgpPeer <- getBgpPeer(subnet)) yield {
            val addr = port.getPortAddress.getAddress
            val prefixLen = subnet.getPrefixLength
            PortBgpInfo(port.getId.asJava, port.getPortMac, s"$addr/$prefixLen",
                        bgpPeer.getAddress.getAddress)
        }
    }

}

case class PortBgpInfo(portId: UUID, mac: String, cidr: String,
                       bgpPeerIp: String)

