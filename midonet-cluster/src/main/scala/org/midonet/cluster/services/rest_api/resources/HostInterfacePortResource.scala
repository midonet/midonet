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
import javax.ws.rs.core.Response

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.models.{Host, HostInterfacePort, Port}
import org.midonet.cluster.rest_api.validation.MessageProperty.{HOST_IS_NOT_IN_ANY_TUNNEL_ZONE, HOST_INTERFACE_IS_USED, PORT_ALREADY_BOUND, getMessage}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
class HostInterfacePortResource @Inject()(hostId: UUID,
                                          resContext: ResourceContext)
    extends MidonetResource[HostInterfacePort](resContext) {

    @GET
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String)
    : HostInterfacePort = {
        val portId = UUID.fromString(id)
        getResource(classOf[Host], hostId).portIds.asScala
            .find(_ == portId)
            .map(getResource(classOf[HostInterfacePort], _))
            .getOrElse(throw new NotFoundHttpException("Host interface port not found"))
    }

    @GET
    @Produces(Array(APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[HostInterfacePort] = {
        val host = getResource(classOf[Host], hostId)
        listResources(classOf[HostInterfacePort], host.portIds.asScala).asJava
    }

    @POST
    @Consumes(Array(APPLICATION_HOST_INTERFACE_PORT_JSON,
                    APPLICATION_JSON))
    override def create(binding: HostInterfacePort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        val store = resContext.backend.store
        val h = store.get(classOf[Topology.Host], hostId).getOrThrow
        if (h.getTunnelZoneIdsList.isEmpty) {
            throw new BadRequestHttpException(
                getMessage(HOST_IS_NOT_IN_ANY_TUNNEL_ZONE, hostId))
        }

        val oldP = store.get(classOf[Topology.Port], binding.portId).getOrThrow
        if (oldP.hasInterfaceName) {
            throw new BadRequestHttpException(
                getMessage(PORT_ALREADY_BOUND, binding.portId))
        }

        store.getAll(classOf[Topology.Port], h.getPortIdsList)
             .getOrThrow
             .find (
                // of those ports in the host, see if any has the same ifc
                _.getInterfaceName == binding.interfaceName
            ) match {
                case Some(conflictingPort) =>
                    throw new BadRequestHttpException(
                        getMessage(HOST_INTERFACE_IS_USED,
                                   binding.interfaceName))
                case _ =>
            }

        val port = getResource(classOf[Port], binding.portId)
        binding.setBaseUri(resContext.uriInfo.getBaseUri)
        binding.create(hostId)
        port.hostId = hostId
        port.interfaceName = binding.interfaceName
        updateResource(port, Response.created(binding.getUri).build())
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        val port = getResource(classOf[Port], id)
        port.hostId = null
        port.interfaceName = null
        updateResource(port, MidonetResource.OkNoContentResponse)
    }

}
