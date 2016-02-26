
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

package org.midonet.cluster.services.containers

import java.util.{ConcurrentModificationException, UUID}

import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.containersLog
import org.midonet.cluster.data.storage.{NotFoundException, Transaction}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.containers.IPSecContainerDelegate.MaxStorageAttempts
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.{Container, Containers}

object IPSecContainerDelegate {
    final val MaxStorageAttempts = 10
}

@Container(name = Containers.IPSEC_CONTAINER, version = 1)
class IPSecContainerDelegate @Inject()(backend: MidonetBackend)
    extends ContainerDelegate {

    private val log = Logger(LoggerFactory.getLogger(s"$containersLog.ipsec"))

    /** This method is called when the container is scheduled at a specified
      * host. It binds the container port to the host
      */
    @throws[Exception]
    override def onScheduled(container: ServiceContainer, hostId: UUID): Unit = {
        val containerId = container.getId.asJava
        if (!container.hasPortId) {
            throw new IllegalArgumentException(
                s"Container $containerId is not connected to a port")
        }
        val portId = container.getPortId.asJava
        val interfaceName = s"vpn-${portId.toString.substring(0, 8)}"

        log info s"Container ${container.getId.asJava} scheduled at host " +
                 s"$hostId: binding port $portId to interface $interfaceName"
        tryTx { tx =>
            val port = tx.get(classOf[Port], portId).toBuilder
                .setHostId(hostId.asProto)
                .setInterfaceName(interfaceName)
                .build()

            // Check the host does not have another port bound to the same
            // interface.
            val host = tx.get(classOf[Host], hostId.asProto)
            val hostPorts = tx.getAll(classOf[Port], host.getPortIdsList.asScala)
            for (hostPort <- hostPorts
                 if hostPort.getInterfaceName == interfaceName) {
                log warn s"Host $hostId already has port ${hostPort.getId.asJava} " +
                         s"bound to interface $interfaceName"
            }
            tx update port
        }
    }

    /** This method is called when the container is reported UP at the host
      * where it was scheduled.
      */
    override def onUp(container: ServiceContainer, status: ContainerStatus): Unit = {
        log debug s"Container up at host ${status.getHostId.asJava} namespace " +
                  s"${status.getNamespaceName} interface ${status.getInterfaceName}"
    }

    /** This method is called when the container is reported DOWN at the host
      * where it was scheduled.
      */
    override def onDown(container: ServiceContainer,
                        @Nullable status: ContainerStatus): Unit = {
        val hostId = if (status ne null) status.getHostId.asJava else null
        log debug s"Container down at host $hostId"
    }

    /** Method called when the container has been unscheduled from the
      * specified host.
      */
    @throws[Exception]
    override def onUnscheduled(container: ServiceContainer, hostId: UUID): Unit = {
        val containerId = container.getId.asJava
        if (!container.hasPortId) {
            throw new IllegalArgumentException(
                s"Container $containerId is not connected to a port")
        }

        log info s"Container $containerId unscheduled from host $hostId: " +
                 "unbinding port"
        tryTx { tx =>
            val port = tx.get(classOf[Port], container.getPortId)
            if (!port.hasHostId || port.getHostId.asJava != hostId) {
                throw new NotFoundException(classOf[Host], hostId.asProto)
            }
            tx update port.toBuilder
                          .clearHostId()
                          .clearInterfaceName()
                          .build()
        } {
            case e: NotFoundException
                if e.clazz == classOf[Port] && e.id == container.getPortId =>
                log debug s"Port ${container.getPortId.asJava} already deleted"
        }
    }

    private def tryTx(f: (Transaction) => Unit)
                     (implicit handler: PartialFunction[Throwable, Unit] =
                         PartialFunction.empty): Unit = {
        var attempt = 1
        var last: Throwable = null
        while (attempt < MaxStorageAttempts) {
            try {
                val tx = backend.store.transaction()
                f(tx)
                tx.commit()
                return
            } catch {
                case e: ConcurrentModificationException =>
                    log warn s"Write $attempt of $MaxStorageAttempts failed due " +
                             s"to a concurrent modification (${e.getMessage})"
                    attempt += 1
                    last = e
                case NonFatal(e) if handler.isDefinedAt(e) =>
                    handler(e)
                    return
            }
        }
        throw last
    }

}
