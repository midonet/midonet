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
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.google.common.util.concurrent.Service.State
import com.google.common.util.concurrent.{AbstractService, ThreadFactoryBuilder}

import org.reflections.Reflections

import rx.Observable
import rx.functions.Func1
import rx.subjects.PublishSubject

import org.midonet.cluster.data.getIdString
import org.midonet.cluster.data.storage.{NotFoundException, StateResult}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.containers.{ContainerExecutors, ContainerService}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.{Host, TunnelZone, TunnelZoneType}
import org.midonet.packets.IPAddr
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction1, makeFunc1}
import org.midonet.util.reactivex._

object VirtualToPhysicalMapper {

    object TunnelZoneMemberOp extends Enumeration {
        val Added, Deleted = Value
    }

    /** Indicates the active state of a local port.
      */
    case class LocalPortActive(portId: UUID, portNumber: Integer, active: Boolean)

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

        private var last: TunnelZone = _

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
    def setPortActive(portId: UUID, portNumber: Integer, active: Boolean,
                      tunnelKey: Long): Future[StateResult] = {
        self.setPortActive(portId, portNumber, active, tunnelKey)
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
                              reflections: Reflections,
                              hostId: UUID)
    extends AbstractService with MidolmanLogging {

    import VirtualToPhysicalMapper._

    override def logSource = "org.midonet.devices.underlay"

    private val vxlanPortMappingService = new VxLanPortMappingService(vt)
    private val gatewayMappingService = new GatewayMappingService(vt)

    // Use a private executor to manage the container handlers. Since the
    // container handler perform I/O operations (e.g. create namespaces, etc.)
    // we cannot use the virtual topology thread since it will block the
    // notifications for all topology devices, and it may overflow the internal
    // buffers of the ObserveOn RX operator.
    val containerExecutor = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("container-service")
                                  .setDaemon(true).build())
    val containerExecutors = new ContainerExecutors(vt.config.containers)
    val ioExecutor = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("container-io")
            .setDaemon(true).build())
    private val containersService =
        new ContainerService(vt, hostId, containerExecutor, containerExecutors,
                             ioExecutor, reflections)

    private val activePorts = new ConcurrentHashMap[UUID, Int]
    private val portsActiveSubject = PublishSubject.create[LocalPortActive]

    private implicit val ec = ExecutionContext.fromExecutor(vt.vtExecutor)

    register(this)

    override def doStart(): Unit = {
        try {
            startService(gatewayMappingService, "gateway mapping")
            startService(vxlanPortMappingService, "VXLAN port mapping")
            startService(containersService, "containers")
            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.error(e.getMessage, e)
                doStop()
                notifyFailed(e)
        }
    }

    override def doStop(): Unit = {
        clearPortsActive().await()

        stopService(containersService, "containers")
        stopService(vxlanPortMappingService, "VXLAN port mapping")
        stopService(gatewayMappingService, "gateway mapping")

        shutdown()

        if (state() != State.FAILED) {
            notifyStopped()
        }
    }

    /**
      * Starts the specified service.
      */
    @throws[IllegalStateException]
    private def startService(service: AbstractService, name: String): Unit = {
        try {
            service.startAsync().awaitRunning()
        } catch {
            case NonFatal(e) =>
                throw new IllegalStateException(s"Failed to start the $name service", e)
        }
    }

    /**
      * Stops the specified service.
      */
    private def stopService(service: AbstractService, name: String): Unit = {
        try {
            service.stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
                log.warn(s"Failed to stop the $name service", e)
        }
    }

    /**
      * Shuts down the executors used by the [[VirtualToPhysicalMapper]].
      */
    private def shutdown(): Unit = {
        // Shutdown the executors.
        containerExecutors.shutdown()
        containerExecutor.shutdown()
        ioExecutor.shutdown()

        if (!containerExecutor.awaitTermination(vt.config.containers
                                                    .shutdownGraceTime.toMillis,
                                                TimeUnit.MILLISECONDS)) {
            containerExecutor.shutdownNow()
        }
        if (!ioExecutor.awaitTermination(vt.config.containers
                                             .shutdownGraceTime.toMillis,
                                         TimeUnit.MILLISECONDS)) {
            ioExecutor.shutdownNow()
        }
    }

    /**
      * Sets the active flag for the specified local port. The method returns
      * a future that indicate the completion of the operation. The future will
      * complete on the virtual topology thread.
      */
    private def setPortActive(portId: UUID, portNumber: Integer, active: Boolean,
                              tunnelKey: Long): Future[StateResult] = {
        backend.stateStore.setPortActive(portId, hostId, active, tunnelKey)
               .observeOn(vt.vtScheduler)
               .doOnNext(makeAction1 { result =>
                   log.debug("Port {} active to {} (owner {})", portId,
                             Boolean.box(active), Long.box(result.ownerId))
                   portsActiveSubject onNext LocalPortActive(portId, portNumber,
                                                             active)
                   if (active) activePorts.putIfAbsent(portId, portNumber)
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
        val futures = for (entry <- activePorts.entrySet().asScala) yield {
            setPortActive(entry.getKey, entry.getValue, active = false,
                          tunnelKey = 0L)
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

    private def recoverableObservable[D <: Device](clazz: Class[D], deviceId: UUID)
    : Observable[D] = {
        VirtualTopology
            .observable[D](clazz, deviceId)
            .onErrorResumeNext(makeFunc1 { e: Throwable => e match {
                case nfe: NotFoundException
                    if getIdString(nfe.id) == deviceId.toString =>
                    log.info("Device {}/{} not found", clazz, deviceId, e)
                    Observable.error(e)
                case _ =>
                    log.error("Device {}/{} error", clazz, deviceId, e)
                    recoverableObservable[D](clazz, deviceId)
            }})
    }

    /**
      * An observable that emits updates for the specified host.
      */
    private def hosts(hostId: UUID): Observable[Host] = {
        recoverableObservable(classOf[Host], hostId)
    }

    /**
      * An observable that emits updates for the specified tunnel zone.
      */
    private def tunnelZones(tunnelZoneId: UUID): Observable[TunnelZoneUpdate] = {
        recoverableObservable(classOf[TunnelZone], tunnelZoneId)
            .flatMap(new TunnelZoneDiff)
    }

}
