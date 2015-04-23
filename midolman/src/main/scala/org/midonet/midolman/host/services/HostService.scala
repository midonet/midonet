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
import java.util.{Set => JSet, UUID}
import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import com.google.inject.name.Named
import rx.{Observer, Subscription}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.models.Topology.Host.Interface
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.conf.HostIdGenerator
import org.midonet.conf.HostIdGenerator.PropertiesFileNotWritableException
import org.midonet.midolman.Midolman
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.state.HostDirectory.{Metadata => HostMetadata}
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.host.updater.InterfaceDataUpdater
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.{StateAccessException, ZkManager}
import org.midonet.util.eventloop.Reactor

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
 * In 2.x this is done via the [[StorageWithOwnership]] interface.
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
                            interfaceDataUpdater: InterfaceDataUpdater,
                            hostZkManager: HostZkManager,
                            zkManager: ZkManager,
                            @Named("directoryReactor") reactor: Reactor)
    extends AbstractService with HostIdProviderService with MidolmanLogging {
    import HostService._

    private final val store = backend.ownershipStore

    private final val timeout = 5 seconds
    @volatile private var hostIdInternal: UUID = null
    @volatile private var hostName: String = "UNKNOWN"
    private val epoch: Long = System.currentTimeMillis

    private val interfacesLatch = new CountDownLatch(1)
    @volatile private var currentInterfaces: Set[InterfaceDescription] = null
    @volatile private var oldInterfaces: Set[InterfaceDescription] = null
    @volatile private var scannerSubscription: Subscription = null

    private val ownerState = new AtomicReference(OwnershipState.Released)
    @volatile private var ownerSubscription: Subscription = null
    private val ownerObserver = new Observer[Set[String]] {
        override def onCompleted(): Unit = {
            ownerSubscription = null
            recreateHostInV2OrShutdown()
        }
        override def onError(e: Throwable): Unit = {
            ownerSubscription = null
            recreateHostInV2OrShutdown()
        }
        override def onNext(o: Set[String]): Unit = o.headOption match {
            case Some(id) =>
                // Reacquire ownership if the hostname has changed.
                val host = getCurrentHost
                if ((host eq null) || (host.getName != hostName)) {
                    recreateHostInV2OrShutdown()
                }
            case None =>
                recreateHostInV2OrShutdown()
        }
    }

    override def logSource = s"org.midonet.host.host-service"

    override def hostId: UUID = hostIdInternal

    /**
     * Starts the host service, by setting the interface scanner callback to
     * update the host interfaces, getting the host name, creating the host
     * in storage, and monitoring the host ownership.
     */
    override def doStart(): Unit = {
        log.info("Starting MidoNet agent host service")
        try {
            scanner.start()
            scannerSubscription = scanner.subscribe(new Observer[Set[InterfaceDescription]] {
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
                        (ownerState.get != OwnershipState.Acquired)) {
                        return
                    }
                    if (backendConfig.useNewStack) {
                        updateInterfacesInV2()
                    } else {
                        updateInterfacesInV1()
                    }
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
        ownerState.set(OwnershipState.Released)
        scanner.stop()

        if (scannerSubscription ne null) {
            scannerSubscription.unsubscribe()
            scannerSubscription = null
        }

        // If the cluster storage is enabled, delete the ownership.
        if (backendConfig.useNewStack) {
            if (ownerSubscription ne null) {
                ownerSubscription.unsubscribe()
                ownerSubscription = null
            }
            try {
                store.deleteOwner(classOf[Host], hostId, hostId.toString)
            } catch {
                case NonFatal(e) =>
                    log.warn("MidoNet agent host service failed to cleanup " +
                             "host ownership", e)
            }
        } else {
            // Disconnect from ZooKeeper: this will cause the ephemeral nodes to
            // disappear.
            zkManager.disconnect()
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
        hostIdInternal = HostIdGenerator.getHostId
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
        if (backendConfig.useNewStack) {
            createInV2()
        } else {
            createInV1()
        }
    }

    /** Creates the host in v1.x storage. */
    @throws[StateAccessException]
    @throws[SerializationException]
    private def createInV1(): Boolean = {
        if (backendConfig.useNewStack)
            return true
        log.debug("Creating/updating host in legacy storage")
        val metadata = new HostMetadata
        metadata.setName(hostName)
        metadata.setEpoch(epoch)
        metadata.setAddresses(getInterfaceAddresses.toArray)
        if (hostZkManager.exists(hostId)) {
            if (!metadata.isSameHost(hostZkManager.get(hostId))) {
                if (hostZkManager.isAlive(hostId)) {
                    return false
                }
            }
            hostZkManager.updateMetadata(hostId, metadata)
        }
        else {
            hostZkManager.createHost(hostId, metadata)
        }
        hostZkManager.makeAlive(hostId)
        hostZkManager.setHostVersion(hostId)
        ownerState.set(OwnershipState.Acquired)

        updateInterfacesInV1()
        true
    }

    /** Creates the host in v2.x storage. */
    private def createInV2(): Boolean = {
        if (!backendConfig.useNewStack)
            return true
        // Only try to acquire ownership if it has been lost.
        if (!ownerState.compareAndSet(OwnershipState.Released,
                                      OwnershipState.Acquiring))
            return false
        log.debug("Creating host in backend storage with owner ID {}", hostId)
        try {
            // Get the current host if it exists.
            val currentHost = getCurrentHost
            // If the host entry exists
            if (currentHost ne null) {
                if (currentHost.getName != hostName) {
                    log.error("Failed to create host {} with name {} because " +
                              "the host exists with name {}", hostId,
                              hostName, currentHost.getName)
                    ownerState.set(OwnershipState.Released)
                    return false
                }

                val newHost = currentHost.toBuilder
                    .clearInterfaces()
                    .addAllInterfaces(getInterfaces.asJava)
                    .build()

                // Take ownership and update host in storage.
                store.update(newHost, hostId.toString, validator = null)
            } else {
                // Create a new host.
                val host = Host.newBuilder
                    .setId(hostId.asProto)
                    .setName(hostName)
                    .addAllInterfaces(getInterfaces.asJava)
                    .build()
                store.create(host, hostId)
            }
            ownerState.set(OwnershipState.Acquired)

            // Monitor ownership.
            monitorOwnership()

            true
        } catch {
            case e @ (_: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: OwnershipConflictException) =>
                log.error("Failed to create host in backend storage with " +
                          "owner identifier {}", hostId, e)
                ownerState.set(OwnershipState.Released)
                false
            case NonFatal(e) =>
                log.error("Error", e)
                false
        }
    }

    private def recreateHostInV2OrShutdown(): Unit = {
        // Only reacquire ownership if the current state is Acquired.
        if (!ownerState.compareAndSet(OwnershipState.Acquired,
                                      OwnershipState.Released)) return

        log.warn("Lost ownership of host: attempting to reacquire in {} ms",
                 Long.box(config.host.waitTimeForUniqueId))

        try {
            Thread.sleep(config.host.waitTimeForUniqueId)
            createHost()
        } catch {
            case NonFatal(e) => shutdown()
        }
    }

    /**
     * Monitors the ownership for the current host, and attempts to retake
     * ownership when the ownership changes.
     */
    private def monitorOwnership(): Unit = {
        // Do not resubscribe, if already subscribed.
        if ((ownerSubscription ne null) && !ownerSubscription.isUnsubscribed) {
            return
        }

        log.debug("Monitoring host ownership")
        ownerSubscription = store.ownersObservable(classOf[Host], hostId)
            .observeOn(reactor.rxScheduler())
            .subscribe(ownerObserver)
    }

    /**
     * Updates the host with the current set of interfaces in V1.x storage.
     */
    private def updateInterfacesInV1(): Unit = {
        log.debug("Updating host interfaces {}: {}", hostId, currentInterfaces)
        interfaceDataUpdater.updateInterfacesData(hostId, null,
                                                  currentInterfaces.asJava)
    }

    /**
     * Updates the host with the current set of interfaces in V2.x storage.
     */
    private def updateInterfacesInV2(): Unit = {
        log.debug("Updating host interfaces {}: {}", hostId, currentInterfaces)
        try {
            var host = getCurrentHost
            host = host.toBuilder
                .clearInterfaces()
                .addAllInterfaces(getInterfaces.asJava)
                .build()
            store.update(host, hostId.toString, validator = null)
        } catch {
            case e @ (_: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: OwnershipConflictException) =>
                log.error("Failed to create host in backend storage with " +
                          "owner identifier {}", hostId, e)
                recreateHostInV2OrShutdown()
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

    /** Returns the current set of interfaces for this host as a set of protocol
      * buffers messages. */
    private def getInterfaces: Set[Interface] = {
        currentInterfaces.map(ZoomConvert.toProto(_, classOf[Interface]))
    }

    /** Returns the current set of IP addresses for this host. */
    private def getInterfaceAddresses: Seq[InetAddress] = {
        currentInterfaces.toList.flatMap(_.getInetAddresses.asScala).toSeq
    }

}
