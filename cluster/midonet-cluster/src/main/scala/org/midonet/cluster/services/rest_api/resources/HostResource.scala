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
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.TextFormat

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.State
import org.midonet.cluster.rest_api.annotation.{AllowGet, AllowList}
import org.midonet.cluster.rest_api.models.{Host, HostState, Interface}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{OkResponse, ResourceContext}

@RequestScoped
@AllowGet(Array(APPLICATION_HOST_JSON_V2,
                APPLICATION_HOST_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_HOST_COLLECTION_JSON_V2,
                 APPLICATION_HOST_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
class HostResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Host](resContext) {

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        if (isAlive(id)) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        val host = getResource(classOf[Host], id).getOrThrow
        if ((host.portIds ne null) && !host.portIds.isEmpty) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }
        deleteResource(classOf[Host], id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, host: Host,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        // We only allow modifying the Flooding Proxy in this method, all other
        // updates are disallowed.
        getResource(classOf[Host], id).map(current =>
            if (host.floodingProxyWeight != null) {
                current.floodingProxyWeight = host.floodingProxyWeight
                updateResource(current, OkResponse)
            } else {
                log.warn("Tried to set flooding proxy weight to null value")
                Response.status(Response.Status.BAD_REQUEST).build()
            }
        ).getOrThrow
    }

    @Path("{id}/interfaces")
    def interfaces(@PathParam("id") hostId: UUID): InterfaceResource = {
        new InterfaceResource(hostId, resContext)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") hostId: UUID): HostInterfacePortResource = {
        new HostInterfacePortResource(hostId, resContext)
    }

    protected override def getFilter(host: Host) = initHost(host)

    protected override def listFilter(host: Host) = { initHost(host); true }

    private def initHost(host: Host): Host = {
        val interfaces = getInterfaces(host.id.toString).getOrThrow
        interfaces.foreach { i =>
            i.hostId = host.id
            i.setBaseUri(resContext.uriInfo.getBaseUri)
        }

        host.alive = isAlive(host.id.toString)
        host.hostInterfaces = interfaces.asJava
        host.addresses = interfaces.flatMap(_.addresses).map(_.toString).asJava
        host
    }

    private def isAlive(id: String): Boolean = {
        getResourceState(classOf[Host], id, MidonetBackend.AliveKey)
            .getOrThrow.nonEmpty
    }

    private def getInterfaces(hostId: String): Future[Seq[Interface]] = {
        getResourceState(classOf[Host], hostId, MidonetBackend.HostKey).map {
            case SingleValueKey(_, Some(value), _) =>
                val builder = State.HostState.newBuilder()
                TextFormat.merge(value, builder)
                val hostState = ZoomConvert.fromProto(builder.build(),
                                                      classOf[HostState])
                hostState.interfaces.asScala
            case _ => List.empty
        }
    }

}
