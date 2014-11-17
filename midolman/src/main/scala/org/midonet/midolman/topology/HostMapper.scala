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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting

import rx.{Observable, Observer, Subscription}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.{Host => TopologyHost}
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone}
import org.midonet.midolman.topology.devices.{Host => SimHost, TunnelZone => SimTunnelZone}
import org.midonet.util.functors._

import akka.actor.ActorSystem

// TODO: Manage unsubscriptions to the Host Mapper
class HostMapper(id: UUID, store: Storage, vt: VirtualTopology)
                (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimHost](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.host-$id"

    private val subscribed = new AtomicBoolean(false)
    private var simHost = emptySimHost
    private val subscriptions = mutable.Map[UUID, Subscription]()
    private val tunnelObs = tunnelObserver
    private val hostObs = hostObserver
    private val outStream = BehaviorSubject.create[SimHost]()

    private def onUnsubscribe(): Unit = {
        for (subscription <- subscriptions.values) {
            subscription.unsubscribe()
        }
        subscriptions.clear()
    }

    private def allTunnelsReceived: Boolean = {
        simHost.tunnelZoneIds.forall(tunnelId =>
                                     simHost.tunnelZones.contains(tunnelId))
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
                val oldHost = simHost
                simHost = ZoomConvert.fromProto(t, classOf[SimHost])

                // Fill in the IPs for tunnel zones we are still in.
                for (tunnelId <- oldHost.tunnelZoneIds.filter(id =>
                                     simHost.tunnelZoneIds.contains(id))) {
                    simHost.tunnelZones(tunnelId) = oldHost.tunnelZones(tunnelId)
                }
                // Unsubscribe from tunnel zones the host is not part of anymore.
                for (tunnelId <- oldHost.tunnelZoneIds.filter(id =>
                                     !simHost.tunnelZoneIds.contains(id))) {
                    subscriptions(tunnelId).unsubscribe()
                    subscriptions.remove(tunnelId)
                }
                // Subscribe to new tunnel zones we are a member of.
                for (tunnelId <- simHost.tunnelZoneIds.filter((id =>
                                     !oldHost.tunnelZoneIds.contains(id)))) {
                    subscriptions(tunnelId) =
                        store.subscribe(classOf[TopologyTunnelZone], tunnelId,
                                        tunnelObs)
                }
                if (allTunnelsReceived) {
                    outStream.onNext(simHost)
                }
            }
        }
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
                val newTunnel = ZoomConvert.fromProto(t, classOf[SimTunnelZone])
                val tunnelId = newTunnel.id

                // Unsubscribe from the tunnel zone if we are not part of it anymore.
                if (!newTunnel.hosts.contains(simHost.id)) {
                    subscriptions(tunnelId).unsubscribe()
                    simHost.tunnelZones.remove(tunnelId)
                } else {
                    simHost.tunnelZones(tunnelId) = newTunnel.hosts(simHost.id)
                }
                if (allTunnelsReceived) {
                    outStream.onNext(simHost)
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

    private def emptySimHost = {
        val simHost = new SimHost
        simHost.tunnelZoneIds = Set.empty
        simHost
    }

    override def observable: Observable[SimHost] = {
        if (subscribed.compareAndSet(false, true)) {
            subscriptions(id) = store.subscribe(classOf[TopologyHost],
                                                id, hostObs)
        }
        outStream
    }
}