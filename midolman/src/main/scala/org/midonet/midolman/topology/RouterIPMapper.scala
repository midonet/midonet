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

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer}

import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.IPv4Addr

/** Given a router ID, produces an observable that publishes the IP addresses
  * for all the router's ports.
  */
class RouterIPMapper(id: UUID, vt: VirtualTopology) extends MidolmanLogging {

    override def logSource: String = "RouterIPMapper-" + id

    private val ipSubject = BehaviorSubject.create[Set[IPv4Addr]]
    val ipObservable: Observable[Set[IPv4Addr]] =
        ipSubject.asObservable.distinctUntilChanged()

    private val ports = mutable.Map[UUID, PortState]()

    val sub = vt.store.observable(classOf[Router], id)
        .observeOn(vt.vtScheduler)
        .subscribe(new RouterObserver)

    private class RouterObserver extends Observer[Router] {
        override def onCompleted(): Unit = ipSubject.onCompleted()
        override def onError(e: Throwable): Unit = ipSubject.onError(e)
        override def onNext(r: Router): Unit = {
            val newPortIds = r.getPortIdsList.map(_.asJava).toSet
            val oldPortIds = ports.keySet
            if (newPortIds != oldPortIds) {
                val addedPortIds = newPortIds diff oldPortIds
                val removedPortIds = oldPortIds diff newPortIds
                log.debug("Ports for router {} updated, added: {}, removed: {}")
                for (portId <- addedPortIds)
                    ports(portId) = new PortState(portId)
                for (portId <- removedPortIds)
                    ports.remove(portId).foreach(_.sub.unsubscribe())
                publish()
            }
        }
    }

    private def publish(): Unit = {
        if (isReady) {
            val ips: Set[IPv4Addr] = ports.values.map(_.ipAddr)(breakOut)
            log.debug("Publishing ips {} for router {}", ips, id)
            ipSubject.onNext(ips)
        }
    }

    private def isReady: Boolean = ports.values.forall(_.ready)

    private class PortState(portId: UUID) {
        val sub = vt.store.observable(classOf[Port], portId)
            .observeOn(vt.vtScheduler)
            .subscribe(new PortObserver)

        var ipAddr: IPv4Addr = null

        def ready: Boolean = ipAddr != null

        private class PortObserver extends Observer[Port] {
            override def onCompleted(): Unit = {
                // Router should be updated with port removed.
            }

            override def onError(e: Throwable): Unit = ipSubject.onError(e)

            override def onNext(p: Port): Unit = {
                ipAddr = IPAddressUtil.toIPv4Addr(p.getPortAddress)
                log.debug("Received update for port {}: IP is {}",
                          portId, ipAddr)
                publish()
            }
        }
    }
}

