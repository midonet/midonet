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
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Port, ServiceContainerGroup, ServiceContainer}
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

    protected override def createFilter(sc: ServiceContainer,
                                        tx: ResourceTransaction): Unit = {
        if (groupId ne null) {
            sc.serviceGroupId = groupId
        }
        super.createFilter(sc, tx)
    }

    private def setStatus(sc: ServiceContainer): ServiceContainer = {
        if (sc.portId eq null)
            return sc
        val port = getResource(classOf[Port], sc.portId)
        if (port.hostId eq null) {
            sc.statusCode = Code.STOPPED
            return sc
        }
        getResourceState(port.hostId.toString, classOf[ServiceContainer],
                         sc.id, MidonetBackend.StatusKey) match {
            case SingleValueKey(_, Some(value), _) =>
                val builder = State.ContainerStatus.newBuilder()
                TextFormat.merge(value, builder)
                val status = builder.build()
                sc.statusCode = status.getStatusCode
                sc.statusMessage = status.getStatusMessage
                sc.hostId = port.hostId
                sc.namespaceName = status.getNamespaceName
                sc.interfaceName = status.getInterfaceName
            case _ =>
                sc.statusCode = Code.STOPPED
        }
        sc
    }

}
