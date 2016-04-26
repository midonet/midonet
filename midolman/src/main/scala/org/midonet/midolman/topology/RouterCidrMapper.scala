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
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeAction0, makeFunc1}

/** Given a router ID, produces an observable that publishes the IP addresses
  * and subnet prefx lengths for all of the router's ports that have a BGP peer.
  */
class RouterCidrMapper(id: UUID, vt: VirtualTopology) extends MidolmanLogging {

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

    val ipObservable = Observable
        .merge(portTracker.refsObservable,
               bgpTracker.refsObservable,
               routerObservable)
        .observeOn(vt.vtScheduler)
        .filter(makeFunc1(isReady))
        .map[Set[String]](makeFunc1(getIps))
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

    private def getIps(gm: GeneratedMessage): Set[String] = {
        // Get IP addresses of all the router's BGP peers.
        val bgpPeerIps = bgpTracker.currentRefs.values.map {
            bgp => toIPv4Addr(bgp.getAddress)
        }
        log.debug("BgpPeer IPs: {}", bgpPeerIps)

        // Return true if the port's subnet contains any of the BGP peers' IPs.
        def hasBgpPeer(p: Port): Boolean = {
            val subnet = IPSubnetUtil.fromProto(p.getPortSubnet)
            val hasPeer = bgpPeerIps.exists(subnet.containsAddress)
            log.debug("Port with subnet {} has BGP peer: {}",
                      subnet, boolean2Boolean(hasPeer))
            hasPeer
        }

        // Return a set containing the IP address and subnet prefix length of
        // any port whose subnet contains the IP address of a BGP peer.
        val ips: Set[String] = portTracker.currentRefs.values.collect {
            case p if hasBgpPeer(p) =>
                val addr = p.getPortAddress.getAddress
                val prefixLen = p.getPortSubnet.getPrefixLength
                s"$addr/$prefixLen"
        }(breakOut)
        log.debug("Publishing IPs: {}", ips)
        ips
    }

}

