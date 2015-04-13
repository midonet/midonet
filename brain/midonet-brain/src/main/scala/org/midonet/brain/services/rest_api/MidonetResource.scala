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

package org.midonet.brain.services.rest_api

import java.net.URI
import java.util
import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core._

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

import com.google.common.util.concurrent.MoreExecutors._
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.MessageOrBuilder

import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.brain.services.rest_api.MidonetMediaTypes._
import org.midonet.brain.services.rest_api.models.{Port, ResourceUris, UriResource}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Commons

@Path("/")
@RequestScoped
class MidonetResource {

    private implicit val ec = ExecutionContext.fromExecutor(sameThreadExecutor())
    private val log = LoggerFactory.getLogger(classOf[MidonetResource])

    // TODO: do this properly, still haven't figured out how to inject in the
    //       server startup
    //
    // TODO: actually, it would be nice to have a wrapper for Storage with
    //       translation to HTTP errors
    //
    // @Context
    // var backend: MidonetBackend =
    // _
    val backend = Vladimir.backend.store
    val renderer = Vladimir.resRenderer

    @Context
    var uriInfo: UriInfo = _

    @GET
    @Produces(Array(APPLICATION_JSON_V5,
                    MediaType.APPLICATION_JSON))
    def application(): models.Application = {
        log.debug(s"${getClass.getName} entered on ${uriInfo.getAbsolutePath}")
        new models.Application(uriInfo.getAbsolutePathBuilder.build())
    }

    @GET
    @Path("/{resource}/{id}")
    def get(@PathParam("id")id: UUID,
            @PathParam("resource")resource: String,
            @HeaderParam("accept") acceptMedia: String) = {

        log.debug(s"GET $resource with id $id for media type $acceptMedia")
        val dtoClass = MidonetMediaTypes.pathToDto(resource)
        val zoomClass = MidonetMediaTypes.pathToZoom(resource)
        val obj = Await.result(backend.get(zoomClass, id), 3 seconds)
        renderer.from(obj, dtoClass, uriInfo.getBaseUri)
    }

    @GET
    @Path("/{resource}$")
    @Produces(Array( // Sweet love of god.. no, it's not doable
            APPLICATION_AD_ROUTE_COLLECTION_JSON,
            APPLICATION_BGP_COLLECTION_JSON,
            APPLICATION_BRIDGE_COLLECTION_JSON,
            APPLICATION_BRIDGE_COLLECTION_JSON_V2,
            APPLICATION_BRIDGE_COLLECTION_JSON_V3,
            APPLICATION_CHAIN_COLLECTION_JSON,
            APPLICATION_HEALTH_MONITOR_COLLECTION_JSON,
            APPLICATION_HOST_COLLECTION_JSON_V2,
            APPLICATION_HOST_COLLECTION_JSON_V3,
            APPLICATION_INTERFACE_COLLECTION_JSON,
            APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON,
            APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON,
            APPLICATION_LOAD_BALANCER_COLLECTION_JSON,
            APPLICATION_POOL_COLLECTION_JSON,
            APPLICATION_POOL_MEMBER_COLLECTION_JSON,
            APPLICATION_PORTGROUP_COLLECTION_JSON,
            APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
            APPLICATION_PORT_COLLECTION_JSON,
            APPLICATION_PORT_V2_COLLECTION_JSON,
            APPLICATION_ROUTER_COLLECTION_JSON,
            APPLICATION_ROUTER_COLLECTION_JSON_V2,
            APPLICATION_TRACE_REQUEST_COLLECTION_JSON,
            APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
            APPLICATION_VIP_COLLECTION_JSON,
            APPLICATION_VTEP_BINDING_COLLECTION_JSON,
            APPLICATION_VTEP_COLLECTION_JSON,
            APPLICATION_VTEP_PORT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON))
    def list(@PathParam("resource")resource: String,
             @HeaderParam("Accept")acceptMedia: String): util.List[Object] = {
        log.info(s"LIST: $resource")
        val dtoClass = MidonetMediaTypes.pathToDto(resource)
        val zoomClass = MidonetMediaTypes.pathToZoom(resource)
        log.debug(s"Request collection for: $acceptMedia, target class: " +
                  s"${dtoClass.getName}, zoom class: ${zoomClass.getName}")
        val n = Await.result(backend.getAll(zoomClass), 3.seconds)
        n.map { renderer.from(_, dtoClass, uriInfo.getBaseUri) } toList
    }

    @GET
    @Path("/{resource}/{id}/{subresource}")
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_IP4_MAC_COLLECTION_JSON,
                    APPLICATION_ROUTE_COLLECTION_JSON,
                    APPLICATION_RULE_COLLECTION_JSON,
                    APPLICATION_RULE_COLLECTION_JSON_V2,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON_V2,
                    APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON,
                    APPLICATION_DHCPV6_HOST_COLLECTION_JSON,
                    APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2,
                    MediaType.APPLICATION_JSON))
    def list(@PathParam("id")id: UUID,
             @PathParam("resource") mainRes: String,
             @PathParam("subresource") subRes: String,
             @HeaderParam("Accept") accepts: String)
    : util.List[UriResource] = {
        log.debug(s"LIST: $accepts")
        val dtoClass = MidonetMediaTypes.pathToDto(mainRes)
        val zoomClass = MidonetMediaTypes.pathToZoom(mainRes)
        val subDtoClass = MidonetMediaTypes.pathToDto(subRes)
        val subZoomClass = MidonetMediaTypes.pathToZoom(subRes)

        val obj = Await.result(backend.get(zoomClass, id), 3 seconds)
        val dto = renderer.from(obj, dtoClass, uriInfo.getBaseUri)

        val allSubRes = backend.get(zoomClass, id).flatMap { proto =>
            val fieldDesc = proto.getDescriptorForType.findFieldByName("port_ids")
            val data = proto.getField(fieldDesc) match {
                case l: util.List[_] => l map {
                    case uuid: Commons.UUID => backend.get(subZoomClass, uuid)
                    case _ => null
                }
                case _ => List.empty
            }
            Future.sequence(data.filterNot(_ == null))
        } map { allProtos => allProtos map { proto =>
            renderer.from(proto, subDtoClass, uriInfo.getBaseUri) }
        }
        Await.result(allSubRes, 3.seconds).toList
    }

    @POST
    @Path("/{mainresource}/{id}/{subresource}")
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    MediaType.APPLICATION_JSON))
    def create(@PathParam("id") id: UUID,
               @PathParam("mainresource") mainRes: String,
               @PathParam("subresource") subRes: String,
               @HeaderParam("Content-Type")contentType: String,
               data: String): Response = {
        log.debug(s"CREATE a sub-resource $subRes of main type $mainRes: $id")
        val mainProtoClass = MidonetMediaTypes.pathToZoom(mainRes)

        try {
            Await.result(backend.get(mainProtoClass, id), 3.seconds)
        } catch {
            case e: NotFoundException =>
                return Response.status(HttpStatus.NOT_FOUND_404)
                               .`type`(mainRes).build()
            case _: Throwable =>
                return Response.serverError().`type`(mainRes).build()
        }

        val subDtoClass = MidonetMediaTypes.pathToDto(subRes)
        val subZoomClass = MidonetMediaTypes.pathToZoom(subRes)
        val dto = deserialize(data, subDtoClass)
        dto match {
            case p: Port => p.setDeviceId(id)
        }

        create(UUID.randomUUID(), dto, subRes, subZoomClass)
    }

    @POST
    @Path("/{resource}$")
    @Consumes(Array(
        APPLICATION_TENANT_JSON,
        APPLICATION_ROUTER_JSON_V2,
        APPLICATION_ROUTER_JSON,
        APPLICATION_BRIDGE_JSON,
        APPLICATION_BRIDGE_JSON_V2,
        APPLICATION_BRIDGE_JSON_V3,
        APPLICATION_HOST_JSON_V2,
        APPLICATION_HOST_JSON_V3,
        APPLICATION_PORT_LINK_JSON,
        APPLICATION_PORTGROUP_JSON,
        APPLICATION_PORTGROUP_PORT_JSON,
        APPLICATION_CHAIN_JSON,
        APPLICATION_RULE_JSON,
        APPLICATION_RULE_JSON_V2,
        APPLICATION_BGP_JSON,
        APPLICATION_AD_ROUTE_JSON,
        APPLICATION_DHCP_SUBNET_JSON,
        APPLICATION_DHCP_SUBNET_JSON_V2,
        APPLICATION_DHCPV6_SUBNET_JSON,
        APPLICATION_TUNNEL_ZONE_JSON,
        APPLICATION_HEALTH_MONITOR_JSON,
        APPLICATION_LOAD_BALANCER_JSON,
        APPLICATION_POOL_MEMBER_JSON,
        APPLICATION_POOL_JSON,
        APPLICATION_VIP_JSON,
        APPLICATION_VTEP_JSON,
        APPLICATION_IP_ADDR_GROUP_JSON,
        APPLICATION_TRACE_REQUEST_JSON,
        MediaType.APPLICATION_JSON))
    def create(@HeaderParam("Content-Type")contentType: String,
               @PathParam("resource")resource: String,
               data: String): Response = {
        log.debug(s"CREATE a $contentType")
        val dto = deserialize(data, MidonetMediaTypes.pathToDto(resource))
        val zoomClass = MidonetMediaTypes.pathToZoom(resource)
        create(UUID.randomUUID(), dto, resource, zoomClass)
    }

    @PUT
    @Path("/{resource}/{id}")
    @Consumes(Array(
        APPLICATION_TENANT_JSON,
        APPLICATION_ROUTER_JSON_V2,
        APPLICATION_ROUTER_JSON,
        APPLICATION_BRIDGE_JSON,
        APPLICATION_BRIDGE_JSON_V2,
        APPLICATION_BRIDGE_JSON_V3,
        APPLICATION_HOST_JSON_V2,
        APPLICATION_HOST_JSON_V3,
        APPLICATION_PORT_LINK_JSON,
        APPLICATION_PORTGROUP_JSON,
        APPLICATION_PORTGROUP_PORT_JSON,
        APPLICATION_CHAIN_JSON,
        APPLICATION_RULE_JSON,
        APPLICATION_RULE_JSON_V2,
        APPLICATION_BGP_JSON,
        APPLICATION_AD_ROUTE_JSON,
        APPLICATION_DHCP_SUBNET_JSON,
        APPLICATION_DHCP_SUBNET_JSON_V2,
        APPLICATION_DHCPV6_SUBNET_JSON,
        APPLICATION_TUNNEL_ZONE_JSON,
        APPLICATION_HEALTH_MONITOR_JSON,
        APPLICATION_LOAD_BALANCER_JSON,
        APPLICATION_POOL_MEMBER_JSON,
        APPLICATION_POOL_JSON,
        APPLICATION_VIP_JSON,
        APPLICATION_VTEP_JSON,
        APPLICATION_IP_ADDR_GROUP_JSON,
        APPLICATION_TRACE_REQUEST_JSON,
        MediaType.APPLICATION_JSON))
    def update(@PathParam("id") id: UUID,
               @PathParam("resource")resource: String,
               @HeaderParam("Content-Type")contentType: String,
               data: String): Response = {
        log.debug(s"UPDATE a $contentType with $data")
        val dto = deserialize(data, MidonetMediaTypes.pathToDto(resource))
        val zoomClass = MidonetMediaTypes.pathToZoom(resource)
        val newProto = renderer.to(dto, zoomClass)
        try {
            backend.update(newProto)
            Response.ok().build()
        } catch {
            case nfe: NotFoundException =>
                Response.status(HttpStatus.NOT_FOUND_404)
                       .`type`(resource).build()
            case _: Throwable =>
                Response.serverError().build()
        }
    }

    @DELETE
    @Path("/{resource}/{id}")
    def delete(@PathParam("id") id: UUID,
               @PathParam("resource") resourceName: String): Unit = {
        log.debug(s"DELETE $resourceName with id $id")
        backend.delete(MidonetMediaTypes.pathToZoom(resourceName), id)
    }

    private def deserialize[T <: UriResource](data: String, dtoClass: Class[T])
    : T = {
        // TODO: make this into a provider or something, I had to put it there
        //       because scala doesn't manage to read the types
        ResourceUris.objectMapper.readValue(data, dtoClass)
    }

    private def create(id: UUID, dto: UriResource, resName: String,
                       zoomClass: Class[_ <: MessageOrBuilder]): Response = {
        try {
            dto.setId(id)
            backend.create(ZoomConvert.toProto(dto, zoomClass))
            Response.created(uriFor(resName, id)).build()
        } catch {
            case NonFatal(e) =>
                log.error("Create failed: ", e)
                Response.serverError().build()
        }
    }

    private def uriFor(resName: String, id: UUID): URI = {
        UriBuilder.fromUri(uriInfo.getBaseUri)
                  .segment(resName)
                  .segment(id.toString).build() // TODO: use path and so on
    }
}
