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

import org.apache.zookeeper.{WatchedEvent, Watcher}

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.midolman.topology.devices.{Host => SimHost, TunnelZone => SimTunnelZone}

import akka.actor.ActorSystem

class HostMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient)
                (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimHost](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.host-$id"

    private val subscribed = new AtomicBoolean(false)
    private var simHost = emptySimHost
    private val aliveWatcher = hostAliveWatcher
    private val subscriptions = mutable.Map[UUID, Subscription]()
    private val tunnelObs = tunnelObserver
    private val hostObs = hostObserver
    private val outStream = BehaviorSubject.create[SimHost]()

    private def hostAliveWatcher: Watcher = new Watcher {
        override def process(event: WatchedEvent) = {
            this.synchronized {
                simHost.alive = dataClient.hostsIsAlive(id, aliveWatcher)
                if (allTunnelsReceived)
                    outStream.onNext(simHost)
            }
        }
    }

    private def onUnsubscribe(): Unit = {
        subscriptions.values.foreach(_.unsubscribe())
        subscriptions.clear()
    }

    private def allTunnelsReceived: Boolean = {
        simHost.tunnelZoneIds.forall(tunnelId =>
            simHost.tunnelZones.contains(tunnelId))
    }

    private def hostObserver: Observer[Proto.Host] = new Observer[Proto.Host] {
        override def onCompleted() = {
            onUnsubscribe()
            outStream.onCompleted()
        }
        override def onError(e: Throwable) = {
            onUnsubscribe()
            outStream.onError(e)
        }
        override def onNext(t: Proto.Host) {
            this.synchronized {
                val oldHost = simHost
                simHost = ZoomConvert.fromProto(t, classOf[SimHost])
                simHost.alive = oldHost.alive

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
                    subscriptions(tunnelId) = vt.store.observable(classOf[Proto.TunnelZone],
                                                                  tunnelId).subscribe(tunnelObs)
                }
                if (allTunnelsReceived)
                    outStream.onNext(simHost)
            }
        }
    }

    private def tunnelObserver: Observer[Proto.TunnelZone] =
        new Observer[Proto.TunnelZone] {

        override def onCompleted() = {
            onUnsubscribe()
            outStream.onCompleted()
        }
        override def onError(e: Throwable) = {
            onUnsubscribe()
            outStream.onError(e)
        }
        override def onNext(t: Proto.TunnelZone) {
            this.synchronized {
                val newTunnel = ZoomConvert.fromProto(t, classOf[SimTunnelZone])
                val tunnelId = newTunnel.id

                // Unsubscribe from the tunnel zone if we are not part of it anymore.
                if (!newTunnel.hosts.contains(simHost.id)) {
                    subscriptions(tunnelId).unsubscribe()
                    simHost.tunnelZones.remove(tunnelId)
                } else
                    simHost.tunnelZones(tunnelId) = newTunnel.hosts(simHost.id)

                if (allTunnelsReceived)
                    outStream.onNext(simHost)
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
            simHost.alive = dataClient.hostsIsAlive(id, aliveWatcher)
            subscriptions(id) = vt.store.observable(classOf[Proto.Host], id).subscribe(hostObs)
        }
        outStream
    }
}