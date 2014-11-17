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

import akka.actor.ActorSystem
import com.google.common.annotations.VisibleForTesting
import org.apache.zookeeper.{WatchedEvent, Watcher}

import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.Host
import org.midonet.midolman.topology.devices.{Host => SimHost, TunnelZone => SimTunnelZone}
import org.midonet.packets.IPAddr
import org.midonet.util.functors._

class HostMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient)
                (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimHost](id, vt) {

    override def logSource = s"${classOf[org.midonet.midolman.topology.devices.Host].getName}-$id"

    private var simHost = emptySimHost
    private var aliveStatusReceived = false
    private val hostAliveStream = BehaviorSubject.create[Boolean]()
    private val aliveWatcher = hostAliveWatcher
    private val tunnelZonesStream = BehaviorSubject.create[Observable[SimTunnelZone]]()
    private val tunnelZones = mutable.Map[UUID, TunnelZoneState]()

    private final class TunnelZoneState(tunnelId: UUID) {
        var hostIp: IPAddr = null
        var mark = PublishSubject.create[SimTunnelZone]()
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
        simHost.tunnelZoneIds = Set.empty
        simHost
    }

    /**
     * @return True iff the HostMapper is observing updates to the given tunnel zone.
     */
    @VisibleForTesting
    protected[topology] def isObservingTunnel(tunnelId: UUID): Boolean =
        tunnelZones.contains(tunnelId)

    private def rebuildHost(host: SimHost): SimHost = {
        // Copying the alive status
        host.alive = simHost.alive
        simHost = host

        // Taking care of new tunnel zones the host is a member of.
        host.tunnelZoneIds.filter(!tunnelZones.keySet.contains(_)).foreach(tzId => {
            tunnelZones(tzId) = new TunnelZoneState(tzId)
            tunnelZonesStream.onNext(tunnelZones(tzId).observable)
        })
        // Taking care of tunnel zones the host is not a member of anymore.
        tunnelZones.keys.filter(!host.tunnelZoneIds.contains(_)).foreach(
            tunnelZones.remove(_)
        )

        simHost
    }

    /**
     * This method handles host, tunnel zones, and host alive status updates.
     * A host update triggers a host rebuild, which does the following:
     * <ol>
     *    <li> it subscribes to new tunnel zones the host is a member of, and
     *    <li> it removes tunnel zones the host is not a member of anymore
     *         (the tunnel zone observable completes whenever the corresponding
     *         tunnel zone id is not part of the host's tunnelZoneId set).
     * </ol>
     *
     * A tunnel zone update leads to storing the host ip in the [[tunnelZones]]
     * map. When all the tunnel zones for the host have been received, the host
     * field tunnelZones is constructed.
     * Updates of the host alive status are reflected in the the alive field of
     * the host.
     */
    private def handleUpdate(update: Any): SimHost = update match {
        case host: SimHost =>
            log.debug(s"Received update for host $id")
            rebuildHost(host)

        case isAlive: Boolean =>
            log.debug(s"The host $id became ${if (isAlive) "alive" else "dead"}")
            aliveStatusReceived = true
            simHost.alive = isAlive
            simHost.deepCopy

        case simTunnel: SimTunnelZone =>
            log.debug(s"Received update for tunnel zone ${simTunnel.id} in host $id")

            // We store the host's ip in the corresponding tunnel zone state.
            // We cannot just keep the ip in the host because we would
            // lose this information when a host update arrives while receiving
            // the tunnel zones the host is a member of.
            tunnelZones(simTunnel.id).hostIp = simTunnel.hosts(id)

            if (areAllTunnelZonesReceived(simHost)) {
                simHost.tunnelZones = tunnelZones.map(idtzState => {
                    (idtzState._1, idtzState._2.hostIp)
                }).toMap
            }
            simHost.deepCopy

        case _ => throw new IllegalArgumentException("The Host Mapper received"
                      + "a device of an unsupported type")
    }

    private def areAllTunnelZonesReceived(host: SimHost) =
        host.tunnelZoneIds.forall(tunnelZones(_).hostIp != null)

    /**
     * This method returns true iff the following has been received at least
     * once:
     * the host, the alive status of the host, and the host IPs for tunnel zones
     * the host is a member of.
     */
    private def hostReady(host: SimHost): Boolean =
        host.id != null && aliveStatusReceived && areAllTunnelZonesReceived(host)

    private def onHostCompleted = makeAction0({
        tunnelZonesStream.onCompleted()
        hostAliveStream.onCompleted()
        tunnelZones.values.foreach(tzState => tzState.complete())
        tunnelZones.clear()
    })

    private def hostObservable = vt.store.observable(classOf[Host], id)
        .map[SimHost](makeFunc1(ZoomConvert.fromProto(_, classOf[SimHost])))
        .doOnCompleted(onHostCompleted)

    override def observable: Observable[SimHost] = {
        hostAliveStream.onNext(dataClient.hostsIsAlive(id, aliveWatcher))
        Observable.merge[Any](hostObservable,
                              hostAliveStream,
                              Observable.merge(tunnelZonesStream))
            .map[SimHost](makeFunc1(handleUpdate))
            .filter(makeFunc1(hostReady(_)))
    }
}