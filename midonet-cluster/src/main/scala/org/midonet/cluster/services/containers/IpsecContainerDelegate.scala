
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

import java.util.UUID

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.apache.commons.lang.NotImplementedException
import org.slf4j.LoggerFactory

import org.midonet.cluster.containersLog
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.containers.Container

@Container(name = "IPSEC", version = 1)
class IpsecContainerDelegate @Inject()(store: Storage) extends ContainerDelegate {

    private val log = Logger(LoggerFactory.getLogger(containersLog))

    /**
      * Method called when the container is created in the backend. At this
      * point the container has been scheduled on the specified agent. The
      * implementation of this method must create a port bound to an interface
      * name.
      */
    override def onCreate(container: ServiceContainer, hostId: UUID): Unit = {
        log.debug(s"Allocation event: container ${container.getId.asJava}" +
                  s"about to be scheduled on host $hostId (create port binding).")
        try {
            val tx = store.transaction()
            val host = tx.get(classOf[Host], hostId)
            // Pre: host belongs to a tunnel zone
            if (host.getTunnelZoneIdsList.isEmpty) {
                log.info(s"Host ${makeReadable(hostId)} does not belong to " +
                         s"any tunnel zone")
                return
            }
            // Pre: interface not bound to a port
            val ifaceName = getInterfaceName(container)
            val hostPorts = tx.getAll(classOf[Port], host.getPortIdsList)
            for (port <- hostPorts) {
                if (port.getInterfaceName == ifaceName) {
                    log.info(
                        s"Host ${makeReadable(hostId)} contains an interface " +
                        s"$ifaceName bound to port ${makeReadable(port.getId)}")
                    return
                }
            }

            val port = tx.get(classOf[Port], container.getPortId)
            val newport = port.toBuilder
                .setId(container.getPortId)
                .setHostId(hostId.asProto)
                .setInterfaceName(ifaceName)
                .build()

            tx.update(newport)
            tx.commit()
        } catch {
            case nfe: NotFoundException =>
                log.info(s"Port ${makeReadable(container.getPortId)} not found.")
        }
    }

    /**
      * Method called when the container namespace and veth port pair are
      * created and connected on the scheduled agent, and the agent has reported
      * the container status as UP. The method arguments will indicate the
      * [[ContainerStatus]] with the host identifier, namespace and interface
      * name where the container is connected and the container health status.
      */
    override def onUp(container: ServiceContainer,
                      status: ContainerStatus): Unit = {
        throw new NotImplementedException()
    }

    /**
      * Method called when the container has been deleted. The implementation
      * of this method must delete the port created for this container.
      */
    override def onDelete(container: ServiceContainer, hostId: UUID): Unit = {
        log.debug(s"Deallocation event: container ${container.getId.asJava}" +
                  s"about to be removed from host $hostId (delete port binding).")
        val tx = store.transaction()
        try {
            val port = tx.get(classOf[Port], container.getPortId)
            val newport = port.toBuilder
                .clearHostId()
                .clearInterfaceName()
                .build()

            tx.update(newport)
            tx.commit()
        } catch {
            case nfe: NotFoundException =>
                log.info(s"Port ${makeReadable(container.getPortId)} not found.")
        }
    }

    /**
      * Method called when the container is no longer available on the scheduled
      * agent. This happens when (1) the agent goes offline (the host alive
      * status is false), (2) the agent encountered a problem with creating or
      * maintaining the container and reported status DOWN, or (3) the container
      * has been deleted.
      */
    override def onDown(container: ServiceContainer,
                        status: ContainerStatus): Unit = {
        throw new NotImplementedException()
    }

    @inline
    private def getInterfaceName(container: ServiceContainer): String = {
        val portId = if (container.getPortId.isInitialized)
            container.getPortId.asJava.toString.substring(0,8) else ""
        s"vpn_${portId}_dp"
    }
}
