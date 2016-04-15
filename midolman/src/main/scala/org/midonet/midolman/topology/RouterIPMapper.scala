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
import scala.collection.{breakOut, mutable}

import com.google.protobuf.GeneratedMessage

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer}

import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.{makeFunc1, makeAction0, makeAction1}

/** Given a router ID, produces an observable that publishes the IP addresses
  * for all the router's ports.
  */
class RouterIPMapper(id: UUID, vt: VirtualTopology) extends MidolmanLogging {

    override def logSource: String = "RouterIPMapper-" + id

    private var portIds: Set[UUID] = _
    private val portTracker = new StoreObjectReferenceTracker[Port](vt, log)

    private val routerObservable = vt.store.observable(classOf[Router], id)
        .observeOn(vt.vtScheduler)
        .filter(makeFunc1(routerUpdated))

    val ipObservable = Observable
        .merge(routerObservable,
               portTracker.refsObservable.observeOn(vt.vtScheduler))
        .observeOn(vt.vtScheduler)
        .filter(makeFunc1(isReady))
        .map[Set[IPv4Addr]](makeFunc1(getIps))
        .distinctUntilChanged()

    private def routerUpdated(r: Router): Boolean = {
        val newPortIds: Set[UUID] = r.getPortIdsList.map(_.asJava)(breakOut)
        log.debug("Router updated. Port ids: " + newPortIds)
        if (newPortIds != portIds) {
            portIds = newPortIds
            portTracker.requestRefs(portIds)
            true
        } else {
            false // No relevant updates.
        }
    }

    private def isReady(gm: GeneratedMessage): Boolean = {
        println("Got message gm: " + gm)
        portIds != null && portTracker.areRefsReady
    }

    private def getIps(gm: GeneratedMessage): Set[IPv4Addr] = {
        val ips: Set[IPv4Addr] = portTracker.currentRefs.values.map {
            port => toIPv4Addr(port.getPortAddress)
        }(breakOut)
        log.debug("Publishing ips {} for router {}", ips, id)
        ips
    }

}

