
/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.containers

import java.util.{ConcurrentModificationException, UUID}

import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.ContainersLog
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{NotFoundException, Transaction}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.ContainerDelegate


abstract class DatapathBoundContainerDelegate (backend: MidonetBackend)
    extends ContainerDelegate {

    def name: String

    private val log = Logger(LoggerFactory.getLogger(s"$ContainersLog.$name"))

    /** This method is called when the container is scheduled at a specified
      * host. It binds the container port to the host
      */
    @throws[Exception]
    override def onScheduled(container: ServiceContainer, hostId: UUID): Unit = {
        val containerId = container.getId.asJava
        if (!container.hasPortId) {
            throw new IllegalArgumentException(
                s"$name container $containerId is not connected to a port")
        }
        val portId = container.getPortId.asJava
        val interfaceName = s"$name-${portId.toString.substring(0, 8)}"

        log info s"$name container ${container.getId.asJava} scheduled at host " +
                 s"$hostId: binding port $portId to interface $interfaceName"
        tryTx { tx =>
            val port = tx.get(classOf[Port], portId)
            val builder = port.toBuilder.setHostId(hostId.asProto)

            if (!port.hasInterfaceName) {
                // If the interface name is not set, set it for backwards
                // compatibility.
                builder.setInterfaceName(interfaceName)
            }

            // Check the host does not have another port bound to the same
            // interface.
            val host = tx.get(classOf[Host], hostId.asProto)
            val hostPorts = tx.getAll(classOf[Port], host.getPortIdsList.asScala)
            for (hostPort <- hostPorts
                 if hostPort.getInterfaceName == interfaceName) {
                log warn s"Host $hostId already has port ${hostPort.getId.asJava} " +
                         s"bound to interface $interfaceName"
            }
            tx update builder.build()
        }
    }

    /** This method is called when the container is reported UP at the host
      * where it was scheduled.
      */
    override def onUp(container: ServiceContainer, status: ContainerStatus): Unit = {
        log debug s"$name container up at host ${status.getHostId.asJava} namespace " +
                  s"${status.getNamespaceName} interface ${status.getInterfaceName}"
    }

    /** This method is called when the container is reported DOWN at the host
      * where it was scheduled.
      */
    override def onDown(container: ServiceContainer,
                        @Nullable status: ContainerStatus): Unit = {
        val hostId = if (status ne null) status.getHostId.asJava else null
        log debug s"$name container down at host $hostId"
    }

    /** Method called when the container has been unscheduled from the
      * specified host.
      */
    @throws[Exception]
    override def onUnscheduled(container: ServiceContainer, hostId: UUID): Unit = {
        val containerId = container.getId.asJava
        if (!container.hasPortId) {
            throw new IllegalArgumentException(
                s"$name container $containerId is not connected to a port")
        }

        log info s"$name container $containerId unscheduled from host $hostId: " +
                 "unbinding port"
        tryTx { tx =>
            val port = tx.get(classOf[Port], container.getPortId)
            if (port.hasHostId && port.getHostId.asJava == hostId) {
                tx update port.toBuilder.clearHostId().build()
            } else {
                log info s"Port ${container.getPortId.asJava} already " +
                         s"unbound from host $hostId"
            }
        } {
            case e: NotFoundException
                if e.clazz == classOf[Port] && e.id == container.getPortId =>
                log debug s"Port ${container.getPortId.asJava} already deleted"
        }
    }

    private def tryTx(f: (Transaction) => Unit)
                     (implicit handler: PartialFunction[Throwable, Unit] =
                         PartialFunction.empty): Unit = {
        try backend.store.tryTransaction(ZoomOwner.ClusterContainers)(f)
        catch {
            case NonFatal(e) if handler.isDefinedAt(e) =>
                handler(e)
        }
    }

}
