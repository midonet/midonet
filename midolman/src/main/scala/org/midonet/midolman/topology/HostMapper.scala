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
import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting
import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Host => TopologyHost}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.HostMapper.TunnelZoneState
import org.midonet.midolman.topology.devices.{Host => SimulationHost, TunnelZone}
import org.midonet.packets.IPAddr
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object HostMapper {

    /**
     * Stores the state for a tunnel zone.
     */
    private final class TunnelZoneState(tunnelZoneId: UUID) {

        private var currentTunnelZone: TunnelZone = null
        private val mark = PublishSubject.create[TunnelZone]

        /** The tunnel zone-observable, notifications on the VT thread. */
        val observable = VirtualTopology
            .observable[TunnelZone](tunnelZoneId)
            .doOnNext(makeAction1(currentTunnelZone = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this tunnel zone state */
        def complete() = mark.onCompleted()
        /** Gets the current tunnel zone or null, if none is set. */
        @Nullable
        def tunnelZone: TunnelZone = currentTunnelZone
        /** Indicates whether the tunnel zone state has received the tunnel
          * zone data */
        def isReady: Boolean = currentTunnelZone ne null
    }

}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationHost]]
 */
final class HostMapper(hostId: UUID, vt: VirtualTopology)
    extends DeviceMapper[SimulationHost](hostId, vt) {

    override def logSource = s"org.midonet.devices.host.host-$hostId"

    private var currentHost: TopologyHost = null
    private var alive: Option[Boolean] = None

    private val tunnelZonesSubject =
        PublishSubject.create[Observable[TunnelZone]]()
    private val tunnelZones = mutable.Map[UUID, TunnelZoneState]()

    /**
     * @return True iff the HostMapper is observing updates to the given tunnel
     *         zone.
     */
    @VisibleForTesting
    protected[topology] def isObservingTunnel(tunnelId: UUID): Boolean =
        tunnelZones.contains(tunnelId)

    /**
     * Processes the host, tunnel zone and alive status updates and indicates
     * if the host device is ready, when the host, alive status and all tunnel
     * zones were received.
     * A host update triggers a host rebuild, which does the following:
     * - it subscribes to new tunnel zones the host is a member of, and
     * - it removes tunnel zones the host is not a member of anymore (the tunnel
     * zone observable completes whenever the corresponding tunnel zone id is
     * not part of the host's tunnelZoneId set).
     * A tunnel zone update leads to storing the host IP in the [[tunnelZones]]
     * map. When all the tunnel zones for the host have been received, the host
     * field tunnelZones is constructed.
     * Updates of the host alive status are reflected in the the alive field of
     * the host.
     */
    private def isHostReady(update: Any): Boolean = {
        assertThread()
        update match {
            case host: TopologyHost =>
                log.debug("Update for host {}", hostId)

                val tunnelZoneIds = Set(
                    host.getTunnelZoneIdsList.asScala.map(_.asJava).toArray: _*)

                // Complete the observables for the tunnel zones no longer part
                // of this host.
                for ((tunnelZoneId, tunnelZoneState) <- tunnelZones.toList
                     if !tunnelZoneIds.contains(tunnelZoneId)) {
                    tunnelZoneState.complete()
                    tunnelZones -= tunnelZoneId
                }

                // Create state for the new tunnel zones of this host, and
                // notify their observable on the tunnel zones observable.
                for (tunnelZoneId <- tunnelZoneIds
                     if !tunnelZones.contains(tunnelZoneId)) {
                    val tunnelZoneState = new TunnelZoneState(tunnelZoneId)
                    tunnelZones += tunnelZoneId -> tunnelZoneState
                    tunnelZonesSubject onNext tunnelZoneState.observable
                }

                currentHost = host
            case a: Boolean =>
                log.debug("Host {} liveness changed: {}", hostId, Boolean.box(a))
                alive = Some(a)
            case tunnelZone: TunnelZone if tunnelZones.contains(tunnelZone.id) =>
                log.debug("Update for tunnel zone {}", tunnelZone.id)
            case _ => log.warn("Unexpected update: ignoring")
        }

        val ready = (currentHost ne null) && alive.isDefined &&
                    tunnelZones.count(!_._2.isReady) == 0
        log.debug("Host {} ready: {}", hostId, Boolean.box(ready))
        ready
    }

    /**
     * A map function that creates the host simulation device from the current
     * host, tunnel-zones and alive information.
     */
    private def deviceUpdated(update: Any): SimulationHost = {
        log.debug("Processing and creating host {} device", hostId)
        assertThread()

        // Compute the tunnel zones to IP mapping for this host.
        val tunnelZoneIps = new mutable.HashMap[UUID, IPAddr]()
        for ((tunnelZoneId, tunnelZoneState) <- tunnelZones) {
            tunnelZoneState.tunnelZone.hosts get hostId match {
                case Some(addr) => tunnelZoneIps += tunnelZoneId -> addr
                case None =>
            }
        }

        val host = ZoomConvert.fromProto(currentHost, classOf[SimulationHost])
        host.alive = alive.get
        host.tunnelZones = tunnelZoneIps.toMap
        host
    }

    /**
     * This method is called when the host observable completes. It triggers a
     * completion of the device observable, by completing all tunnel-zone
     * observables, and the alive observable.
     */
    private def hostDeleted(): Unit = {
        log.debug("Host {} deleted", hostId)
        assertThread()
        tunnelZonesSubject.onCompleted()
        tunnelZones.values.foreach(_.complete())
        tunnelZones.clear()
    }

    /**
     * This function determines if the host is alive based on the set of host
     * owners.
     */
    private def aliveUpdated(owners: Set[String]): Boolean = {
        assertThread()
        owners.nonEmpty
    }

    // Ownership changes modify the version of the host and will thus
    // trigger a host update, hence the 'distinctUntilChanged'.
    private lazy val hostObservable =
        vt.store.observable(classOf[TopologyHost], hostId)
            .observeOn(vt.vtScheduler)
            .distinctUntilChanged
            .doOnCompleted(makeAction0(hostDeleted()))

    private lazy val aliveObservable =
        vt.store.ownersObservable(classOf[TopologyHost], hostId)
            .observeOn(vt.vtScheduler)
            .map[Boolean](makeFunc1(aliveUpdated))
            .distinctUntilChanged
            .onErrorResumeNext(Observable.empty)

    // WARNING! The device observable merges the tunnel-zones, host and alive
    // observable. Publish subjects such as the tunnel-zones observable must be
    // added to the merge before observables that may trigger their update, such
    // as the host observable, which ensures they are subscribed to before
    // emitting any updates.
    protected override lazy val observable: Observable[SimulationHost] =
        Observable.merge[Any](Observable.merge(tunnelZonesSubject),
                              aliveObservable,
                              hostObservable)
                  .filter(makeFunc1(isHostReady))
                  .map[SimulationHost](makeFunc1(deviceUpdated))
                  .distinctUntilChanged
}
