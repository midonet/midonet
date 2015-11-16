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

package org.midonet.midolman.host.services

import java.net.{InetAddress, UnknownHostException}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import java.util.ConcurrentModificationException
import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import com.google.inject.name.Named
import com.google.protobuf.TextFormat
import rx.{Observer, Subscription}

import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.conf.HostIdGenerator.PropertiesFileNotWritableException
import org.midonet.midolman.Midolman
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.services.HostIdProvider
import org.midonet.packets.MAC
import org.midonet.util.eventloop.Reactor
import org.midonet.util.reactivex._

object HostService {
    object OwnershipState extends Enumeration {
        type State = Value
        val Acquiring, Acquired, Released = Value
    }

    val InterfacesTimeoutInSecs = 3

    class HostIdAlreadyInUseException(message: String)
        extends Exception(message)
}

/**
 * A service that upon start creates or updates a [[Host]] entry in the topology
 * storage for the MidoNet agent. The service also monitors and updates the set
 * of interfaces of the host.
 *
 * To prevent multiple instances of the agent from different physical hosts,
 * using the same host identifier, the [[HostService]] leverages an ownership
 * mechanism to indicate whether the host is in use. In both MidoNet 1.x and 2.x
 * this uses a ZooKeeper ephemeral node, to indicate the presence of a host.
 * In 2.x this is done via the [[Storage]] interface.
 *
 * The host ownership also indicates whether the host is active.
 *
 * In MidoNet 2.x, the [[HostService]] also monitors the host ownership to
 * detect topology changes. If the ownership is lost, the service will attempt
 * to reacquire ownership using the same retry policy as the one specified in
 * the configuration. If reacquiring the ownership fails, then the
 * [[HostService]] shuts down according to the behavior specified in the
 * `shutdown()` method.
 */
class HostService @Inject()(config: MidolmanConfig,
                            backendConfig: MidonetBackendConfig,
                            backend: MidonetBackend,
                            scanner: InterfaceScanner,
                            hostIdProvider: HostIdProvider,
                            @Named("directoryReactor") reactor: Reactor)
    extends AbstractService with MidolmanLogging {
    import HostService._

    private final val store = backend.store
    private final val stateStore = backend.stateStore

    private final val hostId = hostIdProvider.hostId()

    private final val timeout = 5 seconds
    @volatile private var hostName: String = "UNKNOWN"

    private val interfacesLatch = new CountDownLatch(1)
    @volatile private var currentInterfaces: Set[InterfaceDescription] = null
    @volatile private var oldInterfaces: Set[InterfaceDescription] = null
    @volatile private var scannerSubscription: Subscription = null

    private val aliveState = new AtomicReference(OwnershipState.Released)
    @volatile private var aliveSubscription: Subscription = null
    private val aliveObserver = new Observer[StateKey] {
        override def onCompleted(): Unit = {
            aliveSubscription = null
            recreateHostOrShutdown()
        }
        override def onError(e: Throwable): Unit = {
            aliveSubscription = null
            recreateHostOrShutdown()
        }
        override def onNext(key: StateKey): Unit = key match {
            case SingleValueKey(_, _, ownerId) =>
                // Reacquire ownership if the owner identifier has changed.
                if (stateStore.ownerId != ownerId) {
                    log.warn("Ownership for the alive state of host has " +
                             "changed (current={} expected={}): reacquiring " +
                             "ownership", Long.box(ownerId),
                             Long.box(stateStore.ownerId))
                    recreateHostOrShutdown()
                }
            case _ =>
                log.error("Unexpected alive notification {} for host {}",
                          key, hostId)
        }
    }

    override def logSource = s"org.midonet.host.host-service"

    /**
     * Starts the host service, by setting the interface scanner callback to
     * update the host interfaces, getting the host name, creating the host
     * in storage, and monitoring the host ownership.
     */
    override def doStart(): Unit = {
        log.info("Starting MidoNet agent host service")
        try {
            scanner.start()
            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                override def onCompleted(): Unit = {
                    log.debug("Interface updating is completed.")
                }
                override def onError(t: Throwable): Unit = {
                    log.error("Got the error: {}", t)
                }
                override def onNext(data: Set[InterfaceDescription]): Unit = {
                    oldInterfaces = currentInterfaces
                    currentInterfaces = data
                    interfacesLatch.countDown()
                    // Do not update if the interfaces have not changed or if the
                    // service has not yet acquired the host ownership.
                    if ((oldInterfaces == currentInterfaces) ||
                        (aliveState.get != OwnershipState.Acquired)) {
                        return
                    }
                    updateInterfaces()
                }
            })
            identifyHost()
            createHost()
            notifyStarted()
            log.info("MidoNet agent host service started")
        }
        catch {
            case NonFatal(e) =>
                log.error("MidoNet agent host service failed to start", e)
                notifyFailed(e)
        }
    }

    /**
     * Stops the host service, by shutting down the interface scanner. In
     * MidoNet 2.x, the method also terminates the ownership monitoring, and
     * removes the ownership entry from the topology store.
     */
    override def doStop(): Unit = {
        log.info("Stopping MidoNet agent host service")
        aliveState.set(OwnershipState.Released)
        scanner.stop()

        if (scannerSubscription ne null) {
            scannerSubscription.unsubscribe()
            scannerSubscription = null
        }

        // If the cluster storage is enabled, delete the ownership.
        if (aliveSubscription ne null) {
            aliveSubscription.unsubscribe()
            aliveSubscription = null
        }
        try {
            val f1 = stateStore.removeValue(classOf[Host], hostId, AliveKey,
                                            null).asFuture
            val f2 = stateStore.removeValue(classOf[Host], hostId,
                                            HostKey, null).asFuture
            Await.ready(f1, timeout)
            Await.ready(f2, timeout)
        } catch {
            case NonFatal(e) =>
                log.warn("MidoNet agent host service failed to cleanup " +
                         "host ownership", e)
        }

        notifyStopped()
        log.info("MidoNet agent host service stopped")
    }

    /**
     * Shuts down the MidoNet agent. You can override this method in a derived
     * class to provide a different behavior.
     */
    def shutdown() = {
        System.exit(Midolman.MIDOLMAN_ERROR_CODE_LOST_HOST_OWNERSHIP)
    }

    /** Scans the host and identifies the host ID. */
    @throws[IllegalStateException]
    @throws[PropertiesFileNotWritableException]
    private def identifyHost(): Unit = {
        log.debug("Identifying host")
        if (!interfacesLatch.await(InterfacesTimeoutInSecs, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "Timeout while waiting for interfaces")
        }
        try {
            hostName = InetAddress.getLocalHost.getHostName
        } catch {
            case e: UnknownHostException =>
        }
    }

    @throws[StateAccessException]
    @throws[InterruptedException]
    @throws[SerializationException]
    @throws[HostIdAlreadyInUseException]
    private def createHost(): Unit = {
        var attempts: Int = 0
        val maxAttempts = config.host.retriesForUniqueId + 1
        while (!create() && { attempts += 1; attempts } < maxAttempts) {
            log.warn("Host ID already in use: waiting for it to be released " +
                "({} of {}).", Int.box(attempts), Int.box(maxAttempts - 1))
            Thread.sleep(config.host.waitTimeForUniqueId)
        }
        if (attempts >= maxAttempts) {
            log.error("Could not acquire ownership: host already in use")
            throw new HostIdAlreadyInUseException(
                s"Host ID $hostId appears to already be taken")
        }
    }

    @inline
    @throws[StateAccessException]
    @throws[SerializationException]
    private def create(): Boolean = {
        // Only try to acquire ownership if it has been lost.
        if (!aliveState.compareAndSet(OwnershipState.Released,
                                      OwnershipState.Acquiring)) {
            return false
        }
        log.debug("Creating host in backend storage with owner ID {}", hostId)
        try {
            // Get the current host if it exists.
            val currentHost = getCurrentHost
            val currentState = getCurrentState
            // If the host or state entries exist.
            if (currentHost ne null) {
                if (!isSameHost(currentHost, currentState)) {
                    log.error("Failed to create host {} with name {} because " +
                              "the host already exists with different name, " +
                              "tunnel-zones or interfaces {}", hostId, hostName)
                    aliveState.set(OwnershipState.Released)
                    return false
                }
            } else {
                // Create a new host.
                val host = Host.newBuilder
                    .setId(hostId.asProto)
                    .setName(hostName)
                    .build()
                store.create(host)
            }

            // Set the alive state and update the interfaces.
            stateStore.addValue(classOf[Host], hostId, AliveKey, AliveKey)
                .await(timeout)
            updateInterfaces()

            aliveState.set(OwnershipState.Acquired)

            // Monitor ownership.
            monitorOwnership()

            true
        } catch {
            case e @ (_: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: ConcurrentModificationException) =>
                log.error("Failed to create host in backend storage", e)
                aliveState.set(OwnershipState.Released)
                false
            case e: NotStateOwnerException =>
                log.error("A different host has ownership of host ID {}",
                          hostId, e)
                aliveState.set(OwnershipState.Released)
                false
            case e: UnmodifiableStateException =>
                log.error("Cannot set the host as alive", e)
                aliveState.set(OwnershipState.Released)
                false
            case NonFatal(e) =>
                log.error("Unexpected error when creating host", e)
                aliveState.set(OwnershipState.Released)
                false
        }
    }

    private def recreateHostOrShutdown(): Unit = {
        // Only reacquire ownership if the current state is Acquired.
        if (!aliveState.compareAndSet(OwnershipState.Acquired,
            OwnershipState.Released)) return

        log.warn("Lost ownership of host: attempting to reacquire in {} ms",
            Long.box(config.host.waitTimeForUniqueId))

        try {
            Thread.sleep(config.host.waitTimeForUniqueId)
            createHost()
        } catch {
            case NonFatal(e) =>
                log.error("Unrecoverable error when acquiring ownership for" +
                          "host {}: calling shutdown method", hostId, e)
                aliveState.set(OwnershipState.Released)
                try { scanner.stop() } catch { case NonFatal(_) => }
                if (scannerSubscription ne null) {
                    scannerSubscription.unsubscribe()
                    scannerSubscription = null
                }
                if (aliveSubscription ne null) {
                    aliveSubscription.unsubscribe()
                    aliveSubscription = null
                }
                shutdown()
        }
    }

    /**
     * Monitors the ownership for the current host, and attempts to retake
     * ownership when the ownership changes.
     */
    private def monitorOwnership(): Unit = {
        // Do not resubscribe, if already subscribed.
        if ((aliveSubscription ne null) && !aliveSubscription.isUnsubscribed) {
            return
        }

        log.debug("Monitoring host active state")

        aliveSubscription = stateStore.keyObservable(classOf[Host], hostId,
                                                     AliveKey)
            .observeOn(reactor.rxScheduler())
            .subscribe(aliveObserver)
    }

    /**
     * Updates the host with the current set of interfaces in V2.x storage.
     */
    private def updateInterfaces(): Unit = {
        def upOrDown(iface: InterfaceDescription) =
                if (iface.isUp) "UP" else "DOWN"
        def pluggedOrNot(iface: InterfaceDescription) =
                if (iface.isUp) "LINK" else "NO_LINK"

        val ifdescs = currentInterfaces map { i =>
            s"${i.getName}<${upOrDown(i)},${pluggedOrNot(i)},${i.getMtu}>"
        }
        log.debug("Updating network interfaces: {}", ifdescs.mkString(", "))

        try {
            stateStore.addValue(classOf[Host], hostId, HostKey, getInterfaces)
                .await(timeout)
        } catch {
            case NonFatal(e) =>
                log.error("Failed to update network interfaces for host {}",
                          hostId, e)
        }
    }

    @Nullable
    @throws[TimeoutException]
    @throws[InterruptedException]
    private def getCurrentHost: Host = {
        Await.ready(store.get(classOf[Host], hostId), timeout).value match {
            case Some(Success(host)) => host
            case _ => null
        }
    }

    @Nullable
    @throws[TimeoutException]
    @throws[InterruptedException]
    private def getCurrentState: State.HostState = {
        Await.ready(stateStore.getKey(classOf[Host], hostId, HostKey).asFuture,
                    timeout).value match {
            case Some(Success(SingleValueKey(_,Some(value),_))) =>
                val builder = State.HostState.newBuilder()
                TextFormat.merge(value, builder)
                builder.build()
            case _ => null
        }
    }

    /** Returns the current set of interfaces for this host as a set of protocol
      * buffers messages. */
    private def getInterfaces: String = {
        State.HostState.newBuilder()
            .setHostId(UUIDUtil.toProto(hostId))
            .addAllInterfaces(
                currentInterfaces.map(
                    ZoomConvert.toProto(_, classOf[State.HostState.Interface]))
                    .asJava)
            .build()
            .toString
    }

    /** Verifies that the current host and state read from storage belong to
      * this host. The function compares the host name, and if the state exists,
      * that at least one interface has the same MAC address. */
    private def isSameHost(currentHost: Host, currentState: State.HostState)
    : Boolean = {
        if (currentHost.getName != hostName) {
            log.debug("Hostname {} different from stored value {}", hostName,
                      currentHost.getName)
            return false
        }
        if (currentState ne null) {
            val currentMacs = currentInterfaces
                .filter(i => (i.getMac ne null) && i.getMac.asLong() != 0L)
                .map(_.getMac)
            val interfaces = currentState.getInterfacesList.asScala
            for (interface <- interfaces) {
                try {
                    val mac = MAC.fromString(interface.getMac)
                    if (currentMacs.contains(mac)) {
                        return true
                    }
                } catch {
                    case NonFatal(_) =>
                }
            }
            currentMacs.isEmpty && interfaces.isEmpty
        } else true
    }

}
