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
  * for all of the router's ports that have a BGP peer.
  */
class RouterIPMapper(id: UUID, vt: VirtualTopology) extends MidolmanLogging {

    override def logSource: String = "RouterIPMapper-" + id

    private var portIds: Set[UUID] = _
    private val portTracker = new StoreObjectReferenceTracker[Port](vt, log)

    private var bgpIds: Set[UUID] = _
    private val bgpTracker = new StoreObjectReferenceTracker[BgpPeer](vt, log)

    private val routerObservable = vt.store.observable(classOf[Router], id)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(routerDeleted()))
        .filter(makeFunc1(routerUpdated))

    val ipObservable = Observable
        .merge(portTracker.refsObservable.observeOn(vt.vtScheduler),
               bgpTracker.refsObservable.observeOn(vt.vtScheduler),
               routerObservable)
        .observeOn(vt.vtScheduler)
        .filter(makeFunc1(isReady))
        .map[Set[IPv4Addr]](makeFunc1(getIps))
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

    private def getIps(gm: GeneratedMessage): Set[IPv4Addr] = {
        val bgpPeerIps = bgpTracker.currentRefs.values.map {
            bgp => toIPv4Addr(bgp.getAddress)
        }

        def hasBgpPeer(p: Port): Boolean = {
            val subnet = IPSubnetUtil.fromProto(p.getPortSubnet)
            bgpPeerIps.exists(subnet.containsAddress)
        }

        portTracker.currentRefs.values.collect {
            case p if hasBgpPeer(p) => toIPv4Addr(p.getPortAddress)
        }(breakOut)
    }

}

