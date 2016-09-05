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

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.StateKey
import org.midonet.cluster.models.Topology.{Host => TopologyHost}
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.topology.DeviceMapper.DeviceState
import org.midonet.midolman.topology.devices.{TunnelZone, Host => SimulationHost}
import org.midonet.util.functors.{makeAction0, makeFunc1}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationHost]].
 */
final class HostMapper(hostId: UUID, vt: VirtualTopology)
    extends DeviceMapper(classOf[SimulationHost], hostId, vt) {

    override def logSource = "org.midonet.devices.host"
    override def logMark = s"host:$hostId"

    private var currentHost: TopologyHost = null
    private var alive: Option[Boolean] = None

    private val tunnelZonesSubject =
        PublishSubject.create[Observable[TunnelZone]]()
    private val tunnelZones = mutable.Map[UUID, TunnelZoneState]()

    private val portsSubject = PublishSubject.create[Observable[Port]]()
    private val ports = mutable.Map[UUID, PortState]()

    /** Stores the state for a tunnel zone. */
    type TunnelZoneState = DeviceState[TunnelZone]

    /** Stores the state for a port. */
    type PortState = DeviceState[Port]

    /**
     * @return True iff the HostMapper is observing updates to the given tunnel
     *         zone.
     */
    @VisibleForTesting
    protected[topology] def isObservingTunnel(tunnelId: UUID): Boolean =
        tunnelZones.contains(tunnelId)

    @VisibleForTesting
    protected[topology] def isObservingPort(portId: UUID): Boolean =
        ports.contains(portId)

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
                val tunnelZoneIds =
                    host.getTunnelZoneIdsList.asScala.map(_.asJava).toSet
                val portIds =
                    host.getPortIdsList.asScala.map(_.asJava).toSet
                log.debug("Host updated with tunnel zones: {} bound ports: {}",
                          tunnelZoneIds, portIds)

                updateTopologyDeviceState(
                    classOf[TunnelZone],
                    tunnelZoneIds, tunnelZones, tunnelZonesSubject,
                    (id: UUID) => {
                        log.debug("Tunnel-zone {} deleted", id)
                        tunnelZones -= id
                    },
                    (id: UUID, e: Throwable) => {
                        log.error("Tunnel-zone {} error and is discarded by " +
                                  "this host", id, e)
                        tunnelZones -= id
                        Observable.just[TunnelZone](null)
                    })
                updateTopologyDeviceState(
                    classOf[Port],
                    portIds, ports, portsSubject,
                    (id: UUID) => {
                        log.debug("Port {} deleted", id)
                        ports -= id
                    },
                    (id: UUID, e: Throwable) => {
                        log.error("Port {} error and is discarded by this host",
                                  id, e)
                        ports -= id
                        Observable.just[Port](null)
                    })

                currentHost = host
            case a: Boolean =>
                log.debug("Host alive changed: {}", Boolean.box(a))
                alive = Some(a)
            case tunnelZone: TunnelZone if tunnelZones.contains(tunnelZone.id) =>
                log.debug("Host tunnel zone updated: {}", tunnelZone.id)
            case port: Port if ports.contains(port.id) =>
                log.debug("Host bound port updated: {}", port)
            case null => // Ignore null updates
            case _ => log.warn("Unexpected update: ignoring")
        }

        val ready = (currentHost ne null) && alive.isDefined &&
                    tunnelZones.forall(_._2.isReady) &&
                    ports.forall(_._2.isReady)
        log.debug("Host ready: {}", Boolean.box(ready))
        ready
    }

    /**
     * A map function that creates the host simulation device from the current
     * host, tunnel-zones, ports and alive information.
     */
    private def deviceUpdated(update: Any): SimulationHost = {
        assertThread()

        // Compute the tunnel zones to IP mapping for this host.
        val tunnelZonesEntries =
            for ((tunnelZoneId, tunnelZoneState) <- tunnelZones;
                 addr <- tunnelZoneState.device.hosts.get(hostId))
                yield tunnelZoneId -> addr

        // Compute the port bindings for this host.
        def portBindings = for ((id, state) <- ports)
            yield id -> (state.device.interfaceName, state.device.previousHostId)

        val host = new SimulationHost(currentHost.getId.asJava,
                                      alive.get,
                                      tunnelZonesEntries.toMap,
                                      portBindings.toMap)

        log.debug("Build host: {}", host)

        host
    }

    /**
     * This method is called when the host observable completes. It triggers a
     * completion of the device observable, by completing all tunnel-zone
     * observables, and the alive observable.
     */
    private def hostDeleted(): Unit = {
        log.debug("Host deleted")
        assertThread()
        tunnelZonesSubject.onCompleted()
        tunnelZones.values.foreach(_.complete())
        tunnelZones.clear()
        portsSubject.onCompleted()
        ports.values.foreach(_.complete())
        ports.clear()
    }

    /**
     * This function determines if the host is alive based on the set of host
     * owners.
     */
    private def aliveUpdated(key: StateKey): Boolean = {
        assertThread()
        key.nonEmpty
    }

    // Ownership changes modify the version of the host and will thus
    // trigger a host update, hence the 'distinctUntilChanged'.
    private lazy val hostObservable =
        vt.store.observable(classOf[TopologyHost], hostId)
            .observeOn(vt.vtScheduler)
            .distinctUntilChanged
            .doOnCompleted(makeAction0(hostDeleted()))

    private lazy val aliveObservable =
        vt.stateStore.keyObservable(classOf[TopologyHost], hostId, AliveKey)
            .observeOn(vt.vtScheduler)
            .map[Boolean](makeFunc1(aliveUpdated))
            .distinctUntilChanged
            .onErrorResumeNext(Observable.empty)

    protected override lazy val observable: Observable[SimulationHost] =
        Observable.merge[Any](Observable.merge(tunnelZonesSubject),
                              Observable.merge(portsSubject),
                              aliveObservable,
                              hostObservable)
                  .filter(makeFunc1(isHostReady))
                  .map[SimulationHost](makeFunc1(deviceUpdated))
                  .distinctUntilChanged
}
