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
import java.util
import java.util.{Set => JSet, UUID}
import java.util.concurrent.{TimeUnit, CountDownLatch}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.{OwnershipConflictException, ReferenceConflictException, ObjectExistsException, StorageWithOwnership}
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.config.HostIdGenerator
import org.midonet.config.HostIdGenerator.PropertiesFileNotWritableException
import org.midonet.midolman.host.config.HostConfig
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

class HostService extends AbstractService with HostIdProviderService
                  with MidolmanLogging {

    @Inject private val zkConfig: ZookeeperConfig = null
    @Inject private val hostConfig: HostConfig = null
    @Inject private val scanner: InterfaceScanner = null
    @Inject private val interfaceDataUpdater: InterfaceDataUpdater = null
    @Inject private val hostZkManager: HostZkManager = null
    @Inject private val zkManager: ZkManager = null
    @Inject private val storage: StorageWithOwnership = null

    private var hostId: UUID = null
    private val epoch: Long = System.currentTimeMillis

    override def doStart(): Unit = {
        log.info("Starting MidoNet Agent host service")
        try {
            identifyHostId()
            scanner.start()
            scanner.register(new Callback[util.Set[InterfaceDescription]] {
                override def onSuccess(data: util.Set[InterfaceDescription])
                : Unit = {
                    interfaceDataUpdater.updateInterfacesData(hostId, null, data)
                }
                override def onError(e: NetlinkException): Unit = { }
            })
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

        // Disconnect from zookeeper: this will cause the ephemeral nodes to
        // disappear.
        zkManager.disconnect()

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
    private def identifyHostId(): Unit = {
        log.debug("Identifying host")
        val metadata = new HostMetadata
        val builder = Host.newBuilder()
        metadata.setEpoch(epoch)
        val listAddresses = new ListBuffer[InetAddress]
        for (info <- getInterfaces) {
            listAddresses ++= info.getInetAddresses.asScala
        }
        metadata.setAddresses(listAddresses.toArray)
        builder.addAllAddresses(listAddresses.map(_.asProto).asJava)
        try {
            metadata.setName(InetAddress.getLocalHost.getHostName)
        }
        catch {
            case e: UnknownHostException => metadata.setName("UNKNOWN")
        }
        builder.setName(metadata.getName)
        hostId = HostIdGenerator.getHostId(hostConfig)
        builder.setId(hostId.asProto)
        var retries: Int = hostConfig.getRetriesForUniqueHostId
        while (!create(hostId, metadata, builder.build()) &&
               {retries -= 1; retries} >= 0) {
            log.warn("Host ID already in use. Waiting for it to be released.")
            Thread.sleep(hostConfig.getWaitTimeForUniqueHostId)
        }
        if (retries < 0) {
            log.error("Couldn't take ownership of the in-use host ID")
            throw new HostService.HostIdAlreadyInUseException(
                "Host ID " + hostId + "appears to already be taken")
        }
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def create(id: UUID, metadata: HostMetadata, host: Host): Boolean = {
        createLegacy(id, metadata) && createCluster(id, host)
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def createLegacy(id: UUID, metadata: HostMetadata): Boolean = {
        if (zkConfig.isLegacyStorageEnabled) {
            if (hostZkManager.exists(id)) {
                if (!metadata.isSameHost(hostZkManager.get(id))) {
                    if (hostZkManager.isAlive(id)) {
                        return false
                    }
                }
                hostZkManager.updateMetadata(id, metadata)
            }
            else {
                hostZkManager.createHost(id, metadata)
            }
            hostZkManager.makeAlive(id)
            hostZkManager.setHostVersion(id)
        }
        true
    }

    private def createCluster(id: UUID, host: Host): Boolean = {
        if (zkConfig.isClusterStorageEnabled) {
            try {
                // If the host entry exists
                if (Await.result(storage.exists(classOf[Host], id), 5 seconds)) {
                    // Try take ownership.
                    storage.updateOwner(classOf[Host], hostId, hostId, true)
                    // Update host object.
                    storage.update(host, hostId, null)
                } else {
                    // Create the host object.
                    storage.create(host, hostId)
                }
            } catch {
                case e: ObjectExistsException => return false
                case e: ReferenceConflictException => return false
                case e: OwnershipConflictException => return false
            }
        }
        true
    }

    override def getHostId: UUID =  hostId

    @SuppressWarnings(Array("unchecked"))
    private def getInterfaces: Set[InterfaceDescription] = {
        val latch: CountDownLatch = new CountDownLatch(1)
        val interfaces = Array.ofDim[JSet[InterfaceDescription]](1)
        val s = scanner.register(new Callback[JSet[InterfaceDescription]] {
            override def onSuccess(data: JSet[InterfaceDescription]): Unit = {
                interfaces(0) = data
                latch.countDown()
            }
            override def onError(e: NetlinkException): Unit = { }
        })

        try {
            latch.await(1, TimeUnit.SECONDS)
        }
        catch {
            case e: InterruptedException =>
                throw new RuntimeException("Timeout while waiting for " +
                                           "interfaces", e)
        }
        s.unsubscribe()
        interfaces(0).asScala.toSet
    }
}
