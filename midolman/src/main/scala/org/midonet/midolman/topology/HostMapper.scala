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

import org.apache.zookeeper.{WatchedEvent, Watcher}

import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.midolman.topology.devices.{Host => SimHost, TunnelZone => SimTunnelZone}
import org.midonet.util.functors._

import akka.actor.ActorSystem

class HostMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient)
                (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimHost](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.host-$id"

    private var simHost = emptySimHost
    private val hostAliveStream = BehaviorSubject.create[Boolean]()
    private val aliveWatcher = hostAliveWatcher
    private val tunnelsStream = BehaviorSubject.create[Observable[SimTunnelZone]]()
    private val tunnels = mutable.Map[UUID, TunnelState]()

    private final class TunnelState(tunnelId: UUID) {
        private val mark = PublishSubject.create[SimTunnelZone]()
        val observable = BehaviorSubject.create[SimTunnelZone]()
        VirtualTopology
            .observable[SimTunnelZone](tunnelId)
            .takeUntil(mark)
            .subscribe(observable)
        def complete() = mark.onCompleted()
    }

    // TODO(nicolas): Rely on Zoom to obtain an observable on the alive
    //                status of the host when available.
    private def hostAliveWatcher: Watcher = new Watcher {
        override def process(event: WatchedEvent) =
            hostAliveStream.onNext(dataClient.hostsIsAlive(id, aliveWatcher))
    }

    private def emptySimHost: SimHost = {
        val simHost = new SimHost
        simHost.tunnelIds = Set.empty
        simHost
    }

    /**
     * @return The subscription to the given deviceId.
     */
    @VisibleForTesting
    protected[topology] def isObservingTunnel(deviceId: UUID): Boolean =
        tunnels.contains(deviceId)

    private def onNextHost(host: SimHost): SimHost = {
        // Taking care of new tunnel zones the host is a member of.
        host.tunnelIds.filter(!tunnels.keySet.contains(_)).foreach(tunnelId => {
            tunnels(tunnelId) = new TunnelState(tunnelId)
            tunnelsStream.onNext(tunnels(tunnelId).observable)
        })
        // Taking care of tunnel zones the host is not a member of anymore.
        tunnels.keys.filter(!host.tunnelIds.contains(_)).foreach(tunnelId => {
            tunnels(tunnelId).complete()
            tunnels.remove(tunnelId)
        })
        // Copying IPs from already existing tunnel zones
        host.tunnels = collection.immutable
            .Map(simHost.tunnels.toSeq: _*)
            .filter(idIp => host.tunnelIds.contains(idIp._1))
        // Copying the alive status
        host.alive = simHost.alive

        simHost = host.clone()
        simHost
    }

    private def onNextDevice(update: Any): SimHost = update match {
        case simHost: SimHost => onNextHost(simHost)
        case isAlive: Boolean =>
            simHost.alive = isAlive
            simHost
        case simTunnel: SimTunnelZone =>
            simHost.tunnels += (simTunnel.id -> simTunnel.hosts(id))
            simHost
        case _ => throw new IllegalArgumentException("The Host Mapper received"
                      + "a device of an unsupported type")
    }

    private def allTunnelsReceived(host: SimHost): Boolean =
        host.tunnelIds.forall(host.tunnels.contains(_))

    private def onHostCompleted = makeAction0({
        tunnelsStream.onCompleted()
        hostAliveStream.onCompleted()
        tunnels.values.foreach(tunnelState => tunnelState.complete())
        tunnels.clear()
    })

    private def hostObservable = vt.store.observable(classOf[Proto.Host], id)
        .map[SimHost](makeFunc1(ZoomConvert.fromProto(_, classOf[SimHost])))
        .doOnCompleted(onHostCompleted)

    override def observable: Observable[SimHost] = {
        hostAliveStream.onNext(dataClient.hostsIsAlive(id, aliveWatcher))
        Observable.merge[Any](hostObservable,
                              hostAliveStream,
                              Observable.merge(tunnelsStream))
            .map[SimHost](makeFunc1(onNextDevice))
            .filter(makeFunc1(allTunnelsReceived(_)))
    }
}