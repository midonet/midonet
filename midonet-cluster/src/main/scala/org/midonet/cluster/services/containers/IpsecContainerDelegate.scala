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
import org.midonet.cluster.models.Topology.{Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._

class IpsecContainerDelegate @Inject()(store: Storage) extends ContainerDelegate {

    private val log = Logger(LoggerFactory.getLogger(containersLog))

    /**
      * Method called when the container is created in the backend. At this
      * point the container has been scheduled on the specified agent. The
      * implementation of this method must create a port bound to an interface
      * name.
      */
    override def onCreate(container: ServiceContainer,
                          group: ServiceContainerGroup,
                          hostId: UUID): Unit = {
        log.debug(s"Allocation event: container ${container.getId.asJava}" +
                  s"about to be scheduled on host $hostId (create port binding).")
        // TODO FIXME: probably need to check that (like HostInterfacePortResource):
        // 1) the host is in a tunnel zone
        // 2) the port is not already bound
        // 3) the interface is not already bound to another port
        val tx = store.transaction()
        val port = tx.get(classOf[Port], container.getPortId)
        val newport = port.toBuilder
            .setId(container.getPortId)
            .setHostId(hostId.asProto)
            .setInterfaceName(s"vpn-${port.getId.asJava.toString.substring(0,8)}_dp")
            .build()

        try {
            tx.update(newport)
            tx.commit()
        } catch {
            case nfe: NotFoundException =>
                log.info(s"Port ${makeReadable(port.getId)} not found.")
        }
    }

    /**
      * Method called when the container namespace and veth port pair are
      * created and connected on the scheduled agent, and the agent has reported
      * the container status as UP. The method arguments will indicate the
      * [[ContainerStatus]] with the host identifier, namespace and interface
      * name where the container is connected and the container health status.
      */
    override def onUp(container: ServiceContainer, group: ServiceContainerGroup,
                      status: ContainerStatus): Unit = {
        throw new NotImplementedException()
    }

    /**
      * Method called when the container has been deleted. The implementation
      * of this method must delete the port created for this container.
      */
    override def onDelete(container: ServiceContainer,
                          group: ServiceContainerGroup,
                          hostId: UUID): Unit = {
        log.debug(s"Deallocation event: container ${container.getId.asJava}" +
                  s"about to be removed from host $hostId (delete port binding).")
        val tx = store.transaction()
        val port = tx.get(classOf[Port], container.getPortId)
        val newport = port.toBuilder
            .clearHostId()
            .clearInterfaceName()
            .build()

        try {
            tx.update(newport)
            tx.commit()
        } catch {
            case nfe: NotFoundException =>
                log.info(s"Port ${makeReadable(port.getId)} not found.")
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
                        group: ServiceContainerGroup,
                        status: ContainerStatus): Unit = {
        throw new NotImplementedException()
    }
}
