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

import java.util
import java.util.UUID
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConversions._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1,
             name = "service_containers",
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

    private var parentResId: UUID = null

    def this(resContext: ResourceContext, parentResId: UUID) = {
        this(resContext)
        this.parentResId = parentResId
    }

    override def createFilter(sc: ServiceContainer,
                              tx: ResourceTransaction): Unit = {
        if (parentResId != null) {
            sc.serviceGroupId = parentResId
        }
        super.createFilter(sc, tx)
    }

}

@ApiResource(version = 1,
             name = "service_container_groups",
             template = "serviceContainerGroupTemplate")
@Path("service_container_groups")
@RequestScoped
@AllowCreate(Array(APPLICATION_SERVICE_CONTAINER_GROUP_JSON,
                   APPLICATION_JSON))
@AllowGet(Array(APPLICATION_SERVICE_CONTAINER_GROUP_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_SERVICE_CONTAINER_GROUP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowDelete
class ServiceContainerGroupResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[ServiceContainerGroup](resContext) {

    @GET
    @Path("{id}/service_containers")
    @Produces(Array(APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON))
    def serviceContainers(@PathParam("id") id: UUID)
    : util.List[ServiceContainer] = {
        // Let's 404 if the SCG doesn't exist
        val scg = getResource(classOf[ServiceContainerGroup], id)
        if (scg.serviceContainerIds != null) {
            scg.serviceContainerIds map {
                getResource(classOf[ServiceContainer], _)
            }
        } else {
            List.empty[ServiceContainer]
        }
    }

    @POST
    @Produces(Array(APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON))
    @Path("{id}/service_containers")
    def createServiceContainers(@PathParam("id") id: UUID,
                                sc: ServiceContainer)
    : ServiceContainerResource = {
        // Let's 404 if the SCG doesn't exist
        val scg = getResource(classOf[ServiceContainerGroup], id)
        new ServiceContainerResource(resContext, id)
    }
}
