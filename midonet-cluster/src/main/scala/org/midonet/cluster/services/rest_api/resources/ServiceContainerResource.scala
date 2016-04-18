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

package org.midonet.cluster.services.rest_api.resources

import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.TextFormat

import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.State
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.rest_api.NotAcceptableHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1,
             name = "serviceContainers",
             template = "serviceContainerTemplate")
@Path("service_containers")
@RequestScoped
@AllowCreate(Array(APPLICATION_SERVICE_CONTAINER_JSON,
                   APPLICATION_JSON))
@AllowGet(Array(APPLICATION_SERVICE_CONTAINER_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_SERVICE_CONTAINER_JSON,
                   APPLICATION_JSON))
@AllowDelete
class ServiceContainerResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[ServiceContainer](resContext) {

    private var groupId: UUID = null

    def this(groupId: UUID, resContext: ResourceContext) = {
        this(resContext)
        this.groupId = groupId
    }

    protected override def getFilter(sc: ServiceContainer): ServiceContainer = {
        setStatus(sc)
    }

    protected override def listIds: Seq[Any] = {
        if (groupId ne null) {
            getResource(classOf[ServiceContainerGroup], groupId)
                .serviceContainerIds.asScala
        } else null
    }

    protected override def listFilter(list: Seq[ServiceContainer])
    : Seq[ServiceContainer] = {
        list.map(setStatus)
    }

    protected override def createFilter(container: ServiceContainer,
                                        tx: ResourceTransaction): Unit = {
        if (groupId ne null) {
            container.serviceGroupId = groupId
        }
        super.createFilter(container, tx)
    }

    protected override def updateFilter(to: ServiceContainer,
                                        from: ServiceContainer,
                                        tx: ResourceTransaction): Unit = {
        to.update(from)

        val container = tx.get(classOf[ServiceContainer], to.id)
        if (container.portId eq null) {
            throw new NotAcceptableHttpException(
                getMessage(CONTAINER_UNSCHEDULABLE, to.id))
        }

        val port = tx.get(classOf[Port], container.portId)
        if (to.hostId != port.hostId) {
            port.hostId = to.hostId
            tx.update(port)
        }
    }

    private def setStatus(container: ServiceContainer): ServiceContainer = {
        if (container.portId eq null)
            return container
        val port = getResource(classOf[Port], container.portId)
        container.hostId = port.hostId
        if (port.hostId eq null) {
            container.statusCode = Code.STOPPED
        } else {
            getResourceState(port.hostId.toString, classOf[ServiceContainer],
                             container.id, MidonetBackend.StatusKey) match {
                case SingleValueKey(_, Some(value), _) =>
                    val builder = State.ContainerStatus.newBuilder()
                    TextFormat.merge(value, builder)
                    val status = builder.build()
                    container.statusCode = status.getStatusCode
                    container.statusMessage = status.getStatusMessage
                    container.hostId = port.hostId
                    container.namespaceName = status.getNamespaceName
                    container.interfaceName = status.getInterfaceName
                case _ =>
                    container.statusCode = Code.STOPPED
            }
        }
        container
    }

}
