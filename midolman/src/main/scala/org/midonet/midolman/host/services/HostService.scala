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

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.conf.HostIdGenerator
import org.midonet.conf.HostIdGenerator.PropertiesFileNotWritableException
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
                            scanner: InterfaceScanner,
                            interfaceDataUpdater: InterfaceDataUpdater,
                            hostZkManager: HostZkManager,
                            zkManager: ZkManager)
    extends AbstractService with HostIdProviderService with MidolmanLogging {

    private final val timeout = 5 seconds
    private var hostId: UUID = null
    private val epoch: Long = System.currentTimeMillis

    override def doStart(): Unit = {
        log.info("Starting MidoNet Agent host service")
        try {
            identifyHostId()
            scanner.register(new Callback[util.Set[InterfaceDescription]] {
                override def onSuccess(data: util.Set[InterfaceDescription])
                : Unit = {
                    // Update the host interfaces only if the legacy storage is
                    // enabled
                    interfaceDataUpdater
                        .updateInterfacesData(hostId, null, data)
                }
                override def onError(e: NetlinkException): Unit = { }
            })
            scanner.start()
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
        metadata.setEpoch(epoch)
        val listAddresses = new ListBuffer[InetAddress]
        for (info <- getInterfaces) {
            listAddresses ++= info.getInetAddresses.asScala
        }
        metadata.setAddresses(listAddresses.toArray)
        try {
            metadata.setName(InetAddress.getLocalHost.getHostName)
        } catch {
            case e: UnknownHostException => metadata.setName("UNKNOWN")
        }
        hostId = HostIdGenerator.getHostId()
        var retries: Int = config.host.retriesForUniqueId
        while (!create(hostId, metadata) && {retries -= 1; retries} >= 0) {
            log.warn("Host ID already in use. Waiting for it to be released.")
            Thread.sleep(config.host.waitTimeForUniqueId)
        }
        if (retries < 0) {
            log.error("Couldn't take ownership of the in-use host ID")
            throw new HostService.HostIdAlreadyInUseException(
                "Host ID " + hostId + "appears to already be taken")
        }
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def create(id: UUID, metadata: HostMetadata): Boolean = {
        createLegacy(id, metadata)
    }

    @throws(classOf[StateAccessException])
    @throws(classOf[SerializationException])
    private def createLegacy(id: UUID, metadata: HostMetadata): Boolean = {
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

        if (!latch.await(1, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout while waiting for interfaces")
        }

        s.unsubscribe()
        interfaces(0).asScala.toSet
    }
}
