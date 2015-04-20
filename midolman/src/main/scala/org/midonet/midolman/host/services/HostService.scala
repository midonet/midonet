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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.{Set => JSet, UUID}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject

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
import org.midonet.midolman.host.services.HostService.{HostServiceCallbacks, HostIdAlreadyInUseException, OwnershipState}
import org.midonet.midolman.host.state.HostDirectory.{Metadata => HostMetadata}
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.host.updater.InterfaceDataUpdater
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.{StateAccessException, ZkManager}
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException

object HostService {
    object OwnershipState extends Enumeration {
        type State = Value
        val Acquiring, Acquired, Released = Value
    }

    class HostIdAlreadyInUseException(message: String)
        extends Exception(message)

    class HostServiceCallbacks {
        def shutdown() = {
            System.exit(Midolman.MIDOLMAN_ERROR_CODE_LOST_HOST_OWNERSHIP)
        }
    }
}

/**
 * A service that upon starts creates or update a [[Host]] entry in the topology
 * storage for this instance of the MidoNet agent. The service also monitors
 * and updates the set of host's interfaces.
 *
 * To prevent multiple instances of the agent, from different physical hosts,
 * using the same host identifier, the [[HostService]] leverages an ownership
 * mechanism to indicate whether the host is in use. In both MidoNet 1.x and 2.x
 * this uses a ZooKeeper ephemeral node, to indicate the presence of a host,
 * however in 2.x this is done via the [[StorageWithOwnership]] interface.
 *
 * The host ownership also indicates whether the host is active.
 *
 * In MidoNet 2.x, the [[HostService]] also monitors the host ownership to
 * detect topology changes. If the ownership is lost, the service will attempt
 * to reacquire ownership using the same retry policy from configuration. If
 * reacquiring the ownership fails, then the [[HostService]] `shuts down` using
 * the method specified in the [[HostServiceCallbacks]].
 */
class HostService @Inject()(config: MidolmanConfig,
                            backendConfig: MidonetBackendConfig,
                            backend: MidonetBackend,
                            scanner: InterfaceScanner,
                            interfaceDataUpdater: InterfaceDataUpdater,
                            hostZkManager: HostZkManager,
                            zkManager: ZkManager,
                            serviceCallbacks: HostServiceCallbacks)
    extends AbstractService with HostIdProviderService with MidolmanLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    private final val store = backend.ownershipStore

    private final val timeout = 5 seconds
    @volatile private var hostIdInternal: UUID = null
    @volatile private var hostName: String = "UNKNOWN"
    private val epoch: Long = System.currentTimeMillis

    private val hostReady = new AtomicBoolean(false)
    private val interfacesLatch = new CountDownLatch(1)
    @volatile private var currentInterfaces: Set[InterfaceDescription] = null
    @volatile private var oldInterfaces: Set[InterfaceDescription] = null
    @volatile private var ownerSubscription: Subscription = null

    override def logSource = s"org.midonet.host.host-service"

    override def hostId: UUID = hostIdInternal

    private val ownerState = new AtomicReference(OwnershipState.Released)
    private val ownerObserver = new Observer[Set[String]] {
        override def onCompleted(): Unit = {
            recreateHostInV2OrShutdown()
        }
        override def onError(e: Throwable): Unit = {
            recreateHostInV2OrShutdown()
        }
        override def onNext(o: Set[String]): Unit = o.headOption match {
            case Some(id) =>
                // Reacquire ownership if the hostname has changed.
                try {
                    val host = Await.result(store.get(classOf[Host], hostId),
                                            timeout)
                    if (host.getName != hostName) {
                        recreateHostInV2OrShutdown()
                    }
                } catch {
                    case NonFatal(e) => recreateHostInV2OrShutdown()
                }
            case None =>
                recreateHostInV2OrShutdown()
        }
    }

    /**
     * Starts the host service, by setting the interface scanner callback to
     * update the host interfaces, getting the host name, creating the host
     * in storage, and monitoring the host ownership.
     */
    override def doStart(): Unit = {
        log.info("Starting MidoNet agent host service")
        try {
            scanner.register(new Callback[JSet[InterfaceDescription]] {
                override def onSuccess(data: JSet[InterfaceDescription])
                : Unit = {
                    oldInterfaces = currentInterfaces
                    currentInterfaces = data.asScala.toSet
                    interfacesLatch.countDown()
                    // Do not update the interfaces if the host is not ready
                    if (!hostReady.get() || oldInterfaces == currentInterfaces) {
                        return
                    }
                    if (backendConfig.useNewStack) {
                        createInV2()
                    } else {
                        interfaceDataUpdater
                            .updateInterfacesData(hostId, null, data)
                    }
                }
                override def onError(e: NetlinkException): Unit = { }
            })
            scanner.start()
            identifyHost()
            createHost()
            monitorOwnership()
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
        scanner.shutdown()

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

    /** Scans the host and identifies the host ID. */
    @throws[IllegalStateException]
    @throws[PropertiesFileNotWritableException]
    private def identifyHost(): Unit = {
        log.debug("Identifying host")
        if (!interfacesLatch.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timeout while waiting for interfaces")
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
        var retries: Int = config.host.retriesForUniqueId
        while (!create() && { retries -= 1; retries } >= 0) {
            log.warn("Host identifier already in use: waiting to be released.")
            Thread.sleep(config.host.waitTimeForUniqueId)
        }
        if (retries < 0) {
            log.error("Could not acquire ownership: host already in use")
            throw new HostIdAlreadyInUseException(
                s"Host identifier $hostId appears to already be taken")
        }
    }

    @throws[StateAccessException]
    @throws[SerializationException]
    private def create(): Boolean = {
        hostReady.set(createInV1() && createInV2())
        hostReady.get
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
        interfaceDataUpdater.updateInterfacesData(hostId, null,
                                                  currentInterfaces.asJava)
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
        log.debug("Creating host in backend storage with owner identifier {}",
                  hostId)
        try {
            // Get the current host if it exists.
            val future = store.exists(classOf[Host], hostId) flatMap { exists =>
                if (exists) store.get(classOf[Host], hostId)
                else Future.successful(null)
            }
            val currentHost = Await.result(future, timeout)
            // If the host entry exists
            if (currentHost ne null) {
                val newHost = currentHost.toBuilder
                    .setName(hostName)
                    .clearInterfaces()
                    .addAllInterfaces(getInterfaces.asJava)
                    .build()

                if (currentHost.getName != newHost.getName) {
                    log.error("Failed to create host {} with name {} because " +
                              "the host exists with name {}", hostId,
                              newHost.getName, currentHost.getName)
                    ownerState.set(OwnershipState.Released)
                    return false
                }

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
            true
        } catch {
            case e @ (_: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: OwnershipConflictException) =>
                log.error("Failed to create host in backend storage with " +
                          "owner identifier {}", hostId, e)
                ownerState.set(OwnershipState.Released)
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
            case NonFatal(e) => serviceCallbacks.shutdown()
        }

        // If the ownership observer has been unsubscribed because of a terminal
        // observable notification, re-subscribe.
        if (ownerSubscription.isUnsubscribed) {
            monitorOwnership()
        }
    }

    /**
     * Monitors the ownership for the current host, and attempts to retake
     * ownership when the ownership changes.
     */
    private def monitorOwnership(): Unit = {
        // Only available for V2 storage.
        if (!backendConfig.useNewStack) return

        log.debug("Monitoring host ownership")
        ownerSubscription = store.ownersObservable(classOf[Host], hostId)
            .subscribe(ownerObserver)
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
