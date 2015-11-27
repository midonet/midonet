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

import java.util
import java.util.UUID
import java.util.concurrent.{Executors, ConcurrentHashMap}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State

import rx.Observable
import rx.functions.Func1
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.{TransactionManager, NotFoundException, StateResult}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.containers.ContainerService
import org.midonet.midolman.topology.devices.{TunnelZoneType, Host, TunnelZone}
import org.midonet.packets.IPAddr
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction1, makeFunc1}
import org.midonet.util.reactivex._

object VirtualToPhysicalMapper extends MidolmanLogging {

    object TunnelZoneMemberOp extends Enumeration {
        val Added, Deleted = Value
    }

    /** Indicates the active state of a local port.
      */
    case class LocalPortActive(portId: UUID, active: Boolean)

    /** Indicates the update to a tunnel zone membership.
      */
    case class TunnelZoneUpdate(zoneId: UUID,
                                zoneType: TunnelZoneType,
                                hostId: UUID,
                                address: IPAddr,
                                op: TunnelZoneMemberOp.Value)

    /** Computes the difference between consecutive tunnel zone updates,
      * translating them to an observable that emits [[TunnelZoneUpdate]]
      * elements.
      */
    private class TunnelZoneDiff
        extends Func1[TunnelZone, Observable[TunnelZoneUpdate]] {

        private var last: TunnelZone = null

        override def call(tunnelZone: TunnelZone): Observable[TunnelZoneUpdate] = {

            def toAdd(hostId: UUID, address: IPAddr) =
                TunnelZoneUpdate(tunnelZone.id, tunnelZone.zoneType, hostId,
                                 address, TunnelZoneMemberOp.Added)

            def toRemove(hostId: UUID, address: IPAddr) =
                TunnelZoneUpdate(last.id, last.zoneType, hostId, address,
                                 TunnelZoneMemberOp.Deleted)

            val updates = new util.ArrayList[TunnelZoneUpdate](
                tunnelZone.hosts.size + (if (last ne null) last.hosts.size else 0))

            if ((last ne null) && last.zoneType == tunnelZone.zoneType) {
                // The tunnel-zone membership has changed but the zone type is
                // the same: remove the old members, add new members and replace
                // those whose IP addresses have changed.
                for ((hostId, address) <- last.hosts) {
                    tunnelZone.hosts.get(hostId) match {
                        case None =>
                            updates add toRemove(hostId, address)
                        case Some(addr) if addr != address =>
                            updates add toRemove(hostId, address)
                            updates add toAdd(hostId, addr)
                        case _ =>
                    }
                }
                for ((hostId, address) <- tunnelZone.hosts
                     if !last.hosts.contains(hostId)) {
                    updates add toAdd(hostId, address)
                }
            } else {
                if (last ne null) {
                    // The tunnel zone type has changed: remove current members.
                    for ((hostId, address) <- last.hosts) {
                        updates add toRemove(hostId, address)
                    }
                }
                // New tunnel zone or the zone type has changed: add all members.
                for ((hostId, address) <- tunnelZone.hosts) {
                    updates add toAdd(hostId, address)
                }
            }

            last = tunnelZone
            Observable.from(updates)
        }
    }

    private[topology] var self: VirtualToPhysicalMapper = _

    /**
      * Sets the active flag for the specified local port. The method returns
      * a future that indicate the completion of the operation. The future will
      * complete on the virtual topology thread.
      */
    def setPortActive(portId: UUID, active: Boolean): Future[StateResult] = {
        self.setPortActive(portId, active)
    }

    /**
      * An observable that emits notifications when the status of a local port
      * has been updated in the backend storage. The observable will not emit
      * an update if a request to update the local port active status has
      * failed. Updates emitted by this observable are scheduled on the virtual
      * topology thread.
      */
    def portsActive: Observable[LocalPortActive] = {
        self.portsActive
    }

    /**
      * An observable that emits updates for the specified host. Notifications
      * are emitted on the virtual topology thread.
      */
    def hosts(hostId: UUID): Observable[Host] = {
        self.hosts(hostId)
    }

    /**
      * An observable that emits updates for the specified tunnel zone.
      * Notifications are emitted on the virtual topology thread.
      */
    def tunnelZones(tunnelZoneId: UUID): Observable[TunnelZoneUpdate] = {
        self.tunnelZones(tunnelZoneId)
    }

    /**
      * Registers a virtual to physical mapper instance to this companion
      * object.
      */
    private def register(vtpm: VirtualToPhysicalMapper): Unit = {
        self = vtpm
    }

}

class VirtualToPhysicalMapper(backend: MidonetBackend,
                              vt: VirtualTopology,
                              hostId: UUID)
    extends AbstractService with MidolmanLogging {

    import VirtualToPhysicalMapper._

    override def logSource = "org.midonet.devices.underlay"

    private val vxlanPortMappingService = new VxLanPortMappingService(vt)

    // Use a private executor to manage the container handlers. Since the
    // container handler perform I/O operations (e.g. create namespaces, etc.)
    // we cannot use the virtual topology thread since it will block the
    // notifications for all topology devices, and it may overflow the internal
    // buffers of the ObserveOn RX operator.
    private val containerService =
        new ContainerService(vt, hostId, Executors.newSingleThreadExecutor(
            new NamedThreadFactory("containers", isDaemon = true)))

    private val activePorts = new ConcurrentHashMap[UUID, Boolean]
    private val portsActiveSubject = PublishSubject.create[LocalPortActive]

    private implicit val ec = ExecutionContext.fromExecutor(vt.vtExecutor)

    register(this)

    override def doStart(): Unit = {
        try {
            vxlanPortMappingService.startAsync().awaitRunning()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start the VXLAN port mapping service", e)
                notifyFailed(e)
                doStop()
                return
        }
        try {
            containerService.startAsync().awaitRunning()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start the Containers service", e)
                notifyFailed(e)
                doStop()
                return
        }

        notifyStarted()
    }

    override def doStop(): Unit = {
        clearPortsActive().await()

        try {
            containerService.stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
        }
        try {
            vxlanPortMappingService.stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
        }

        if (state() != State.FAILED) {
            notifyStopped()
        }
    }

    /**
      * Sets the active flag for the specified local port. The method returns
      * a future that indicate the completion of the operation. The future will
      * complete on the virtual topology thread.
      */
    private def setPortActive(portId: UUID, active: Boolean): Future[StateResult] = {
        backend.stateStore.setPortActive(portId, hostId, active)
               .observeOn(vt.vtScheduler)
               .doOnNext(makeAction1 { result =>
                   log.debug("Port {} active to {} (owner {})", portId,
                             Boolean.box(active), Long.box(result.ownerId))
                   portsActiveSubject onNext LocalPortActive(portId, active)
                   if (active) activePorts.putIfAbsent(portId, true)
                   else activePorts.remove(portId)
               })
               .doOnError(makeAction1 { e =>
                   log.error("Failed to set port {} active to {}", portId,
                             Boolean.box(active), e)
               })
               .asFuture
    }

    /**
      * Clears the active flag from all current local ports and returns a future
      * that completes when the update has finished.
      */
    private def clearPortsActive(): Future[_] = {
        val futures = for (portId: UUID <- activePorts.keySet().asScala.toSet) yield {
            setPortActive(portId, active = false)
        }
        activePorts.clear()
        Future.sequence(futures)
    }

    /**
      * An observable that emits notifications when the status of a local port
      * has been updated in the backend storage. The observable will not emit
      * an update if a request to update the local port active status has
      * failed. Updates emitted by this observable are scheduled on the virtual
      * topology thread.
      */
    private def portsActive: Observable[LocalPortActive] = {
        portsActiveSubject.asObservable()
    }

    private def recoverableObservable[D <: Device](deviceId: UUID)
                                                  (implicit t: ClassTag[D])
    : Observable[D] = {
        VirtualTopology
            .observable[D](deviceId)
            .onErrorResumeNext(makeFunc1 { e: Throwable => e match {
                case nfe: NotFoundException
                    if TransactionManager.getIdString(nfe.id.getClass, nfe.id) ==
                       deviceId.toString =>
                    log.info("Device {}/{} not found", t.runtimeClass,
                             deviceId, e)
                    Observable.error(e)
                case _ =>
                    log.error("Device {}/{} error", t.runtimeClass, deviceId, e)
                    recoverableObservable[D](deviceId)
            }})
    }

    /**
      * An observable that emits updates for the specified host.
      */
    private def hosts(hostId: UUID): Observable[Host] = {
        recoverableObservable[Host](hostId)
    }

    /**
      * An observable that emits updates for the specified tunnel zone.
      */
    private def tunnelZones(tunnelZoneId: UUID): Observable[TunnelZoneUpdate] = {
        recoverableObservable[TunnelZone](tunnelZoneId)
            .flatMap(new TunnelZoneDiff)
    }

}
