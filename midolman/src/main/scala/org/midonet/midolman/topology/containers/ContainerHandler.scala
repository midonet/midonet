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

package org.midonet.midolman.topology.containers

import java.util.UUID

import scala.concurrent.Future

/**
  * Identifies a container created by the implementation of a container handler.
  * Currently, the container is uniquely identified by a container identifier,
  * which is tied to the identifier of the container port.
  */
case class ContainerDescriptor(id: UUID) extends AnyVal

/**
  * A container handler provides a specific implementation for each container
  * type at the agent side. A container handler should, among other things,
  * create and delete the container namespace and VETH interface pair for a
  * specified exterior port. In addition, the container handler will ensure
  * that the appropriate services are started and configured for the given
  * service type and configuration, will update the container status, and may
  * provide container health-monitoring.
  */
trait ContainerHandler {

    /**
      * Creates a container for the specified exterior port and service
      * container. The port container the interface name that the container
      * handler should create
      *
      *
      * The container
      * should provide a local interface with the same name as the one
      * specified in `interfaceName` field of the port object. The handler
      * implementation can use the `container` field of the port object to
      * retrieve the container configuration.
      */
    @throws[Exception]
    def create(port: ContainerPort): Future[String]

    /**
      * Deletes the container for the specified exterior port and namespace
      * information.
      */
    @throws[Exception]
    def delete(descriptor: ContainerDescriptor): Future[Int]

}
