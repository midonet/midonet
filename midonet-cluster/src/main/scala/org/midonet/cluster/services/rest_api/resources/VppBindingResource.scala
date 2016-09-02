/*
 * Copyright 2016 Midokura SARL
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

import java.util.{UUID, List => JList}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{Host, Port, VppBinding}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
class VppBindingResource @Inject()(hostId: UUID,
                                   resourceContext: ResourceContext)
    extends MidonetResource[VppBinding](resourceContext) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_HOST_VPP_BINDING_JSON))
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): VppBinding = {
        val portId = UUID.fromString(id)
        getResource(classOf[Host], hostId).portIds.asScala
            .find(_ == portId)
            .map(getResource(classOf[Port], _))
            .filter(_.vppBinding ne null)
            .map(port => {
                port.vppBinding.setBaseUri(resourceContext.uriInfo.getBaseUri)
                port.vppBinding.create(hostId, portId)
                port.vppBinding
            })
            .getOrElse(throw new NotFoundHttpException(
                getMessage(HOST_VPP_BINDING_NOT_FOUND, hostId, portId)))
    }

    @GET
    @Produces(Array(APPLICATION_HOST_VPP_BINDING_COLLECTION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[VppBinding] = {
        val host = getResource(classOf[Host], hostId)
        listResources(classOf[Port], host.portIds.asScala)
            .filter(_.vppBinding ne null)
            .map(port => {
                port.vppBinding.setBaseUri(resourceContext.uriInfo.getBaseUri)
                port.vppBinding.create(hostId, port.id)
                port.vppBinding
            })
            .asJava
    }

    @POST
    @Consumes(Array(APPLICATION_HOST_VPP_BINDING_JSON,
                    APPLICATION_JSON))
    override def create(binding: VppBinding,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = tryTx { tx =>
        val host = tx.get(classOf[Host], hostId)
        if (host.tunnelZoneIds.isEmpty) {
            throw new ConflictHttpException(
                getMessage(HOST_IS_NOT_IN_ANY_TUNNEL_ZONE, hostId))
        }
        val port = tx.get(classOf[Port], binding.portId)
        if ((port.interfaceName ne null) || (port.vppBinding ne null)) {
            throw new ConflictHttpException(
                getMessage(PORT_ALREADY_BOUND, binding.portId))
        }

        binding.setBaseUri(resourceContext.uriInfo.getBaseUri)
        binding.create(hostId, port.id)
        port.hostId = hostId
        port.vppBinding = binding
        tx.update(port)
        Response.created(binding.getUri).build()
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = tryTx { tx =>
        val port = tx.get(classOf[Port], id)

        if (port.vppBinding eq null) {
            throw new BadRequestHttpException(getMessage(PORT_NOT_BOUND, id))
        }

        port.previousHostId = port.hostId
        port.hostId = null
        port.vppBinding = null
        tx.update(port)
        MidonetResource.OkNoContentResponse
    }

}
