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

import akka.actor.ActorSystem
import com.google.common.annotations.VisibleForTesting
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Topology => Proto}
import org.midonet.midolman.topology.devices.{Host => SimHost, TunnelZone => SimTunnelZone}
import org.midonet.packets.IPAddr
import org.midonet.util.functors._
import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject}

import scala.collection.mutable

class HostMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient)
                (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimHost](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.host-$id"

    private var simHost = emptySimHost
    private var aliveStatusReceived = false
    private val hostAliveStream = BehaviorSubject.create[Boolean]()
    private val aliveWatcher = hostAliveWatcher
    private val tunnelsStream = BehaviorSubject.create[Observable[SimTunnelZone]]()
    private val tunnels = mutable.Map[UUID, TunnelState]()

    private final class TunnelState(tunnelId: UUID) {
        var hostIp: Option[IPAddr] = None

        private val mark = PublishSubject.create[SimTunnelZone]()
        val observable = VirtualTopology
            .observable[SimTunnelZone](tunnelId)
            .takeUntil(mark)
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
     * @return True iff the HostMapper is observing updates to the given tunnel zone.
     */
    @VisibleForTesting
    protected[topology] def isObservingTunnel(tunnelId: UUID): Boolean =
        tunnels.contains(tunnelId)

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
        // Copying the alive status
        host.alive = simHost.alive

        simHost = host
        simHost
    }

    private def onNextDevice(update: Any): SimHost = update match {
        case simHost: SimHost => onNextHost(simHost)
        case isAlive: Boolean =>
            aliveStatusReceived = true
            simHost.alive = isAlive
            simHost.clone()

        case simTunnel: SimTunnelZone =>
            // We store the host's ip in the corresponding tunnel state.
            // We cannot just keep the ip in the host directly because we would
            // loose this information when a host update arrives while receiving
            // the host's IPs.
            tunnels(simTunnel.id).hostIp = Option(simTunnel.hosts(id))

            if (allTunnelsReceived(simHost)) {
                simHost.tunnelIds.foreach(tunnelId =>
                    simHost.tunnels += (tunnelId -> tunnels(tunnelId).hostIp.get)
                )
            }
            simHost.clone()

        case _ => throw new IllegalArgumentException("The Host Mapper received"
                      + "a device of an unsupported type")
    }

    private def allTunnelsReceived(host: SimHost) =
        host.tunnelIds.forall(tunnels(_).hostIp.isDefined)

    /**
     * This method returns true iff the following has been received at least
     * once:
     * the host, the alive status of the host, and the host IPs for tunnels
     * the host is a member of.
     */
    private def hostReady(host: SimHost): Boolean =
        host.id != null && aliveStatusReceived && allTunnelsReceived(host)

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
            .filter(makeFunc1(hostReady(_)))
    }
}