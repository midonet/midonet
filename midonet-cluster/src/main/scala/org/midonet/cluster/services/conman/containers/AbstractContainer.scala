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

package org.midonet.cluster.services.conman.containers

import org.midonet.cluster.models.Services.{ServiceContainerGroup, ServiceContainer}
import org.midonet.cluster.models.State.{ContainerStatus, ContainerAgent}

/**
  * Allows the implementation of custom handlers for the service containers.
  * Classes existing this trait may provide custom operations for container
  * state transitions (e.g. a container is up, a container's location has
  * changed, etc.).
  *
  * The cluster Container Management service will load from the current class
  * path all service containers that are annotated with a
  * [[org.midonet.cluster.services.conman.Container]] annotation, indicating
  * the container type name and version.
  *
  * The lifetime of a service container is the following:
  *
  *                (1) onCreate
  *                The container is created via the
  *                API and is being scheduled on
  *   +---------+  a specific agent.    +---------+
  *   | DELETED |---------------------->| CREATED |
  *   |         |                       |         |
  *   +---------+                       +---------+
  *        | (4) onDelete                    | (2) onUp
  *        | The container is deleted via    | The container has been connected
  *        | API.                            | on the scheduled agent, and the
  *        |                                 | agent reported the container up.
  *   +---------+  (2) onUp             +---------+
  *   |  DOWN   |---------------------->|   UP    |
  *   |         |<----------------------|         |
  *   +---------+  (3) onDown           +---------+
  *                The container has been disconnected
  *                from the scheduled agent, because the
  *                container is being deleted, agent
  *                reported status down and it is being
  *                rescheduled on a different agent.
  */
trait AbstractContainer {

    /**
      * Method called when the container is created in the backend. At this
      * point the container has been scheduled on a particular agent, but
      * the agent has not yet reported the container status UP.
      */
    def onCreate(container: ServiceContainer, group: ServiceContainerGroup)

    /**
      * Method called when the container namespace and veth port pair was
      * created and connected on the scheduled agent, and the agent has reported
      * the container status as UP. The method arguments will indicate the
      * [[ContainerAgent]] with the host identifier and interface name where
      * the container is connected, and the [[ContainerStatus]].
      */
    def onUp(container: ServiceContainer, group: ServiceContainerGroup,
             agent: ContainerAgent, status: ContainerStatus)

    /**
      * Method called when the container is no longer available on the scheduled
      * agent. This happens when (1) the agent goes offline (the host alive
      * status is false), (2) the agent encountered a problem with creating or
      * maintaining the container and reported status DOWN, or (3) the container
      * has been deleted.
      */
    def onDown(container: ServiceContainer, group: ServiceContainerGroup,
               agent: ContainerAgent, status: ContainerStatus)

    /**
      * Method called when the container has been deleted. If the agent is alive
      * the method is called only after the agent has reported the container
      * status as DOWN.
      */
    def onDelete(container: ServiceContainer, group: ServiceContainerGroup)

}
