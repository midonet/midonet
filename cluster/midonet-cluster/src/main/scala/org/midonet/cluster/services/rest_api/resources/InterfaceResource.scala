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

import java.util.{List => JList, UUID}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{Host, Interface}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
class InterfaceResource @Inject()(hostId: UUID, resContext: ResourceContext)
    extends MidonetResource[Interface](resContext) {

    @GET
    @Path("{name}")
    @Produces(Array(APPLICATION_INTERFACE_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("name") name: String,
                     @HeaderParam("Accept") accept: String): Interface = {
        getResource(classOf[Host], hostId)
            .map(_.hostInterfaces.asScala.find(_.name == name)
                                         .map(setInterface))
            .getOrThrow
            .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
    }

    @GET
    @Produces(Array(APPLICATION_INTERFACE_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[Interface] = {
        getResource(classOf[Host], hostId)
            .map(_.hostInterfaces.asScala.map(setInterface).asJava)
            .getOrThrow
    }

    private def setInterface(interface: Interface): Interface = {
        interface.hostId = hostId
        interface.setBaseUri(resContext.uriInfo.getBaseUri)
        interface
    }
}
