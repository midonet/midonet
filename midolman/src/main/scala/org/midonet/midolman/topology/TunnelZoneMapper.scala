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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting

import rx.{Observable, Observer, Subscription}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.{Host => TopologyHost}
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone}
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.topology.devices.{TunnelZone => SimTunnelZone}
import org.midonet.util.functors._

import akka.actor.ActorSystem

class TunnelZoneMapper (id: UUID, store: Storage, vt: VirtualTopology)
                           (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimTunnelZone](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.tunnelzone-$id"

    private var simTunnel: SimTunnelZone = emptySimTunnel
    private var hosts: mutable.Map[UUID, SimHost] = mutable.HashMap[UUID, SimHost]()
    private var subscriptions: mutable.Map[UUID, Subscription] =
        mutable.HashMap[UUID, Subscription]()
    private var tunnelObs: Observer[TopologyTunnelZone] = tunnelObserver
    private var hostObs: Observer[TopologyHost] = hostObserver
    private var outStream: BehaviorSubject[SimTunnelZone] =
        BehaviorSubject.create[SimTunnelZone]()

    private def onUnsubscribe(): Unit = {
        for (subscription <- subscriptions.values) {
            subscription.unsubscribe()
        }
    }

    private def allHostsReceived: Boolean = {
        simTunnel.hostIds.forall(hostId => hosts.contains(hostId))
    }

    private def tunnelObserver: Observer[TopologyTunnelZone] =
        new Observer[TopologyTunnelZone] {

        override def onCompleted() = {
            onUnsubscribe()
            outStream.onCompleted()
        }
        override def onError(e: Throwable) = {
            onUnsubscribe()
            outStream.onError(e)
        }
        override def onNext(t: TopologyTunnelZone) {
            this.synchronized {
                val oldTunnelZone = simTunnel
                simTunnel = ZoomConvert.fromProto(t, classOf[SimTunnelZone])
                // Fill in the IPs of hosts that are still in the tunnel zone.
                for (hostId <- oldTunnelZone.hostIds.filter(id =>
                                   simTunnel.hostIds.contains(id))) {
                    simTunnel.IPs ++= hosts(hostId).addresses
                }
                // Unsubscribe from hosts not in the tunnel zone anymore.
                for (hostId <- oldTunnelZone.hostIds.filter(id =>
                                   !simTunnel.hostIds.contains(id))) {
                    subscriptions(hostId).unsubscribe()
                    subscriptions.remove(hostId)
                }
                // Subscribe to new hosts in the tunnel zone.
                for (hostId <- simTunnel.hostIds.filter(id =>
                                   !oldTunnelZone.hostIds.contains(id))) {
                    subscriptions(hostId) =
                        store.subscribe[TopologyHost](classOf[TopologyHost],
                                                      hostId, hostObserver)
                }
                if (allHostsReceived) {
                    outStream.onNext(simTunnel)
                }
            }
        }
    }

    private def hostObserver: Observer[TopologyHost] = new Observer[TopologyHost] {
        override def onCompleted() = {
            onUnsubscribe()
            outStream.onCompleted()
        }
        override def onError(e: Throwable) = {
            onUnsubscribe()
            outStream.onError(e)
        }
        override def onNext(t: TopologyHost) {
            this.synchronized {
                val newHost = ZoomConvert.fromProto(t, classOf[SimHost])

                if (hosts.contains(newHost.id)) {
                    val oldHost = hosts(newHost.id)
                    // Remove IP addresses the host does not have anymore
                    for (ipAddr <- oldHost.addresses.filter(ip =>
                                       !newHost.addresses.contains(ip))) {
                        simTunnel.IPs.remove(ipAddr)
                    }
                }
                simTunnel.IPs ++= newHost.addresses
                hosts(newHost.id) = newHost

                if (allHostsReceived) {
                    outStream.onNext(simTunnel)
                }
            }
        }
    }

    /**
     * @return The subscription to the given deviceId.
     */
    @VisibleForTesting
    protected[topology] def subscription(deviceId: UUID): Option[Subscription] = {
        subscriptions.get(deviceId)
    }

    private def emptySimTunnel = {
        val simTunnel = new SimTunnelZone
        simTunnel.hostIds = Set.empty
        simTunnel
    }

    override def observable: Observable[SimTunnelZone] = {
        subscriptions(id) = store.subscribe[TopologyTunnelZone](classOf[TopologyTunnelZone],
                                                                id, tunnelObs)
        outStream.asObservable().doOnUnsubscribe(makeAction0{ onUnsubscribe() })
    }
}
