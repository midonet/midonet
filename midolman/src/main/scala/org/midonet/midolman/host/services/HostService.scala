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

import java.net.{UnknownHostException, InetAddress}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Set => JSet, UUID}
import java.util.concurrent.{TimeUnit, CountDownLatch}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject

import rx.{Subscription, Observer}

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
import org.midonet.midolman.host.services.HostService.HostIdAlreadyInUseException
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
    class HostIdAlreadyInUseException(message: String)
        extends Exception(message)
}

class HostService @Inject()(config: MidolmanConfig,
                            backendConfig: MidonetBackendConfig,
                            backend: MidonetBackend,
                            scanner: InterfaceScanner,
                            interfaceDataUpdater: InterfaceDataUpdater,
                            hostZkManager: HostZkManager,
                            zkManager: ZkManager)
    extends AbstractService with HostIdProviderService with MidolmanLogging {

    private final val store = backend.ownershipStore

    private final val timeout = 5 seconds
    private var hostId: UUID = null
    private val ownerId = UUID.randomUUID
    private var hostName: String = "UNKNOWN"
    private val epoch: Long = System.currentTimeMillis

    private val hostReady = new AtomicBoolean(false)
    private val interfacesLatch = new CountDownLatch(1)
    @volatile private var interfaces: Set[InterfaceDescription] = null
    @volatile private var ownerSubscription: Subscription = null

    override def logSource = s"org.midonet.host.host-$hostId"

    override def getHostId: UUID =  hostId

    override def doStart(): Unit = {
        log.info("Starting MidoNet Agent host service")
        try {
            scanner.register(new Callback[JSet[InterfaceDescription]] {
                override def onSuccess(data: JSet[InterfaceDescription])
                : Unit = {
                    interfaces = data.asScala.toSet
                    interfacesLatch.countDown()
                    // Do not update the interfaces if the host is not ready
                    if (!hostReady.get()) return
                    if (backendConfig.useNewStack) {
                        updateInBackend()
                    } else {
                        interfaceDataUpdater
                            .updateInterfacesData(hostId, null, data)
                    }
                }
                override def onError(e: NetlinkException): Unit = { }
            })
            scanner.start()
            identifyHost()
            notifyStarted()
            log.info("MidoNet Agent host service started")
        }
        catch {
            case e: Exception =>
                log.error("MidoNet Agent host service failed to start", e)
                notifyFailed(e)
        }
    }

    override def doStop(): Unit = {
        log.info("Stopping MidoNet Agent host service")
        scanner.shutdown()

        // If the cluster storage is enabled, delete the ownership.
        if (backendConfig.useNewStack) {
            try {
                store.deleteOwner(classOf[Host], hostId, hostId.toString)
            } catch {
                case e @ (_: NotFoundException |
                          _: OwnershipConflictException) =>
                    log.error("MidoNet Agent host service failed to cleanup " +
                              "host ownership")
            }
        } else {
            // Disconnect from zookeeper: this will cause the ephemeral nodes to
            // disappear.
            zkManager.disconnect()
        }

        notifyStopped()
        log.info("MidoNet Agent host service stopped")
    }

    /**
     * Scans the host and identifies the host ID.
     */
    @throws(classOf[StateAccessException])
    @throws(classOf[PropertiesFileNotWritableException])
    @throws(classOf[InterruptedException])
    @throws(classOf[SerializationException])
    @throws(classOf[HostIdAlreadyInUseException])
    private def identifyHost(): Unit = {
        log.debug("Identifying host")
        if (!interfacesLatch.await(1, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout while waiting for interfaces")
        }
        hostId = HostIdGenerator.getHostId
        try {
           hostName = InetAddress.getLocalHost.getHostName
        } catch {
            case e: UnknownHostException =>
        }
        createHost()
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[PropertiesFileNotWritableException])
    @throws(classOf[InterruptedException])
    @throws(classOf[SerializationException])
    @throws(classOf[HostIdAlreadyInUseException])
    private def createHost(): Unit = {
        var retries: Int = config.host.retriesForUniqueId
        while (!create() && { retries -= 1; retries } >= 0) {
            log.warn("Host identifier already in use: waiting for it to be " +
                     "released.")
            Thread.sleep(config.host.waitTimeForUniqueId)
        }
        if (retries < 0) {
            log.error("Couldn't take ownership of the in-use host ID")
            throw new HostService.HostIdAlreadyInUseException(
                s"Host identifier $hostId appears to already be taken")
        } else {
            monitorOwnership()
        }
    }

    private def createHostOrExit(): Unit = {
        try {
            Thread.sleep(config.host.waitTimeForUniqueId)
            createHost()
        } catch {
            case NonFatal(e) =>
                System.exit(Midolman.MIDOLMAN_ERROR_CODE_LOST_HOST_OWNERSHIP)
        }
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def create(): Boolean = {
        hostReady.set(createInLegacy() && createInBackend())
        hostReady.get
    }

    /** Creates the host in the legacy storage. */
    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def createInLegacy(): Boolean = {
        if (backendConfig.useNewStack)
            return true
        log.debug("Creating/updating host in legacy storage")
        val metadata = new HostMetadata
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
        true
    }

    /** Creates the host in the backend storage. */
    private def createInBackend(): Boolean = {
        if (!backendConfig.useNewStack)
            return true
        log.debug("Creating/updating host in backend storage with owner " +
                  "identifier {}", ownerId)
        try {
            // If the host entry exists
            if (Await.result(store.exists(classOf[Host], hostId), timeout)) {
                // Read the current host.
                val currentHost =
                    Await.result(store.get(classOf[Host], hostId), timeout)
                val host = currentHost.toBuilder
                    .setName(hostName)
                    .clearInterfaces()
                    .addAllInterfaces(getInterfaces.asJava)
                    .build()
                // Take ownership and update host in storage.
                store.update(host, ownerId.toString, validator = null)
            } else {
                // Create a new host.
                val host = Host.newBuilder
                    .setId(hostId.asProto)
                    .setName(hostName)
                    .addAllInterfaces(getInterfaces.asJava)
                    .build()
                // Create the host object.
                store.create(host, ownerId)
            }
            true
        } catch {
            case e @ (_: NotFoundException |
                      _: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: OwnershipConflictException) =>
                log.debug("Failed to create/update host in backend storage " +
                          "with owner identifier {}", ownerId, e)
                false
        }
    }

    /** Updates the host in the backend storage. */
    private def updateInBackend(): Unit = {
        try {
            // Read the current host.
            val currentHost =
                Await.result(store.get(classOf[Host], hostId), timeout)
            val host = currentHost.toBuilder
                .setName(hostName)
                .clearInterfaces()
                .addAllInterfaces(getInterfaces.asJava)
                .build()
            // Take ownership and update host in storage.
            store.update(host, ownerId.toString, validator = null)
        } catch {
            case e @ (_: NotFoundException |
                      _: ObjectExistsException |
                      _: ReferenceConflictException |
                      _: OwnershipConflictException) =>
                log.debug("Failed to update host in backend storage with " +
                          "owner identifier {}", ownerId, e)
        }
    }

    /**
     * Monitors the ownership for the current host, and attempts to retake
     * ownership when the ownership changes.
     */
    private def monitorOwnership(): Unit = {
        // Only available for backend storage.
        if (!backendConfig.useNewStack) return

        if (null != ownerSubscription) {
            ownerSubscription.unsubscribe()
            ownerSubscription = null
        }

        log.debug("Monitoring host ownership")
        ownerSubscription = store.ownersObservable(classOf[Host], hostId).subscribe(
            new Observer[Set[String]] {
                override def onCompleted(): Unit = {
                    log.warn("Lost ownership of host: attempting to retake " +
                             "ownership in {} ms",
                             Long.box(config.host.waitTimeForUniqueId))
                    createHostOrExit()
                }
                override def onError(e: Throwable): Unit = {
                    log.warn("Lost ownership of host: attempting to retake " +
                             "ownership in {} ms",
                             Long.box(config.host.waitTimeForUniqueId), e)
                    createHostOrExit()
                }
                override def onNext(o: Set[String]): Unit = o.headOption match {
                    case Some(id) if id != ownerId.toString =>
                        log.warn("Lost host ownership to owner {}: attempting " +
                                 "to retake ownership in {} ms")
                        identifyHost()
                    case None =>
                        log.warn("Lost ownership of host: attempting to retake " +
                                 "ownership in {} ms",
                                 Long.box(config.host.waitTimeForUniqueId))
                        createHostOrExit()
                    case _ => log.debug("Host ownership updated")
                }
            })
    }

    /** Returns the current set of interfaces for this host as a set of protocol
      * buffers messages. */
    private def getInterfaces: Set[Interface] = {
        interfaces.map(ZoomConvert.toProto(_, classOf[Interface]))
    }

    /** Returns the current set of IP addresses for this host. */
    private def getInterfaceAddresses: Seq[InetAddress] = {
        val interfaces = this.interfaces
        val addresses = new ListBuffer[InetAddress]
        for (interface <- interfaces) {
            addresses ++= interface.getInetAddresses.asScala
        }
        addresses
    }
}
