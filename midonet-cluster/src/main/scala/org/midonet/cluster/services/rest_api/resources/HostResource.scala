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
import javax.ws.rs.core.Response.Status

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.TextFormat

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.State
import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.annotation.{ApiResource, AllowGet, AllowList}
import org.midonet.cluster.rest_api.models.{TunnelZone, Host, HostState, Interface}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1)
@Path("hosts")
@RequestScoped
@AllowGet(Array(APPLICATION_HOST_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_HOST_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
class HostResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Host](resContext) {

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = tryTx { tx =>
        if (isAlive(id)) {
            return buildErrorResponse(
                Status.FORBIDDEN,
                getMessage(HOST_DELETION_NOT_ALLOWED_ACTIVE, id))
        }
        val host = tx.get(classOf[Host], id)
        if ((host.portIds ne null) && !host.portIds.isEmpty) {
            return buildErrorResponse(
                Status.FORBIDDEN,
                getMessage(HOST_DELETION_NOT_ALLOWED_BINDINGS,
                           id, Integer.valueOf(host.portIds.size())))
        }
        val tunnelZones = tx.list(classOf[TunnelZone], host.tunnelZoneIds.asScala)
        for (tunnelZone <- tunnelZones) {
            tunnelZone.removeHost(host.id)
            tx.update(tunnelZone)
        }
        tx.delete(classOf[Host], id)
        OkResponse
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_HOST_JSON_V3,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, host: Host,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = tryTx { tx =>
        // We only allow modifying the Flooding Proxy in this method, all other
        // updates are disallowed.
        val current = tx.get(classOf[Host], id)
        if (host.floodingProxyWeight != null) {
            current.floodingProxyWeight = host.floodingProxyWeight
            tx.update(current)
            OkResponse
        } else {
            buildErrorResponse(
                Status.BAD_REQUEST,
                getMessage(HOST_FLOODING_PROXY_WEIGHT_IS_NULL))
        }
    }

    @Path("{id}/interfaces")
    def interfaces(@PathParam("id") hostId: UUID): InterfaceResource = {
        new InterfaceResource(hostId, resContext)
    }

    @Path("{id}/ports")
    def ports(@PathParam("id") hostId: UUID): HostInterfacePortResource = {
        new HostInterfacePortResource(hostId, resContext)
    }

    protected override def getFilter(host: Host): Host = {
        initHost(host)
    }

    protected override def listFilter(hosts: Seq[Host]): Seq[Host] = {
        hosts foreach initHost
        hosts
    }

    private def initHost(host: Host): Host = {
        val interfaces = getInterfaces(host.id.toString)
        interfaces.foreach { i =>
            i.hostId = host.id
            i.setBaseUri(resContext.uriInfo.getBaseUri)
        }

        host.alive = isAlive(host.id.toString)
        host.hostInterfaces = interfaces.asJava
        host.addresses = interfaces.flatMap(_.addresses).map(_.getHostAddress).asJava
        host
    }

    private def isAlive(id: String): Boolean = {
        getResourceState(id.toString, classOf[Host], id, MidonetBackend.AliveKey)
            .nonEmpty
    }

    private def getInterfaces(hostId: String): Seq[Interface] = {
        getResourceState(hostId.toString,classOf[Host], hostId,
                         MidonetBackend.HostKey) match {
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
