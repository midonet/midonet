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
import javax.ws.rs.core.Response.Status.NOT_FOUND

import scala.collection.JavaConverters._
import scala.concurrent.Await

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.models.{Host, HostInterfacePort, Port}
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
        getResource(classOf[Host], hostId)
            .flatMap(_.portIds.asScala
                         .find(_ == portId)
                         .map(getResource(classOf[HostInterfacePort], _))
                         .getOrElse(throw new WebApplicationException(NOT_FOUND)))
            .getOrThrow
    }

    @GET
    @Produces(Array(APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[HostInterfacePort] = {
        getResource(classOf[Host], hostId)
            .flatMap(host => listResources(classOf[HostInterfacePort],
                                           host.portIds.asScala))
            .getOrThrow
            .asJava
    }

    @POST
    @Consumes(Array(APPLICATION_HOST_INTERFACE_PORT_JSON,
                    APPLICATION_JSON))
    override def create(binding: HostInterfacePort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        val store = resContext.backend.store
        val h = Await.result(store.get(classOf[Topology.Host], hostId),
                             MidonetResource.Timeout)

        if (h.getTunnelZoneIdsList.isEmpty) {
            throw new BadRequestHttpException(s"Host $hostId does not belong " +
                                              "to any tunnel zones")
        }

        import scala.collection.JavaConversions._
        Await.result(store.getAll(classOf[Topology.Port], h.getPortIdsList),
                     MidonetResource.Timeout) find { p =>
            p.getInterfaceName == binding.interfaceName
        } match {
            case Some(conflictingPort) =>
                throw new BadRequestHttpException(
                    s"Interface ${binding.interfaceName} at host $hostId is" +
                    s"already bound to port ${conflictingPort.getId}")
            case _ =>
        }

        getResource(classOf[Port], binding.portId).map(port => {
            binding.setBaseUri(resContext.uriInfo.getBaseUri)
            binding.create(hostId)
            port.hostId = hostId
            port.interfaceName = binding.interfaceName
            updateResource(port, Response.created(binding.getUri).build())
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        getResource(classOf[Port], id).map(port => {
            port.hostId = null
            port.interfaceName = null
            updateResource(port, MidonetResource.OkNoContentResponse)
        }).getOrThrow
    }

}
