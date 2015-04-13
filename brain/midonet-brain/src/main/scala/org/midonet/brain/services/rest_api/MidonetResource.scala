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
import java.util.{List => JList}

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.google.common.util.concurrent.MoreExecutors._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.MessageOrBuilder

import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.brain.services.rest_api.MidonetMediaTypes._
import org.midonet.brain.services.rest_api.models.{ResourceUris, UriResource}
import org.midonet.brain.services.rest_api.render.ResourceRenderer
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{ObjectExistsException, ReferenceConflictException, ObjectReferencedException, NotFoundException}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._

@Path("/")
@RequestScoped
class MidonetResource @Inject()(implicit backend: MidonetBackend,
                                renderer: ResourceRenderer) {

    private implicit val ec = ExecutionContext.fromExecutor(sameThreadExecutor())
    private final val log = LoggerFactory.getLogger(classOf[MidonetResource])
    private final val timeout = 5 seconds

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
    def get(@PathParam("id") id: String,
            @PathParam("resource") resource: String,
            @HeaderParam("accept") acceptMedia: String) = {
        log.debug(s"GET $resource with id $id for media type $acceptMedia")
        getResource(resource, id)
    }

    @GET
    @Path("/{resource}/{id}/{subresource}/{sid}")
    def get(@PathParam("id") id: String,
            @PathParam("sid") sid: String,
            @PathParam("resource") resource: String,
            @PathParam("subresource") subresource: String,
            @HeaderParam("accept") acceptMedia: String) = {
        log.debug(s"GET $resource with id $id for media type $acceptMedia")
        getResource(resource, id, subresource, sid)
    }

    @GET
    @Path("/{resource}$")
    @Produces(Array(APPLICATION_AD_ROUTE_COLLECTION_JSON,
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
    def list(@PathParam("resource") resource: String,
             @HeaderParam("Accept") acceptMedia: String): util.List[UriResource] = {
        log.info(s"LIST: $resource")
        listResource(resource).asJava
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
                    APPLICATION_INTERFACE_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2,
                    MediaType.APPLICATION_JSON))
    def list(@PathParam("id") id: String,
             @PathParam("resource") resource: String,
             @PathParam("subresource") subresource: String,
             @HeaderParam("Accept") accepts: String)
    : util.List[UriResource] = {
        log.debug(s"LIST: $accepts")
        val x = listResource(resource, id, subresource).asJava
        x
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
    def create(@HeaderParam("Content-Type") contentType: String,
               @PathParam("resource") resource: String,
               data: String): Response = {
        log.debug(s"CREATE a $contentType")
        val dtoAttr = MidonetMediaTypes.ResourceToDtoAttributes(Seq(resource))
        val dto = deserialize(data, dtoAttr.clazz)
        create(UUID.randomUUID(), dto, resource, dtoAttr.zoom.clazz)
        // TODO: createResource(data, resource)
    }

    @POST
    @Path("/{resource}/{id}/{subresource}")
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    MediaType.APPLICATION_JSON))
    def create(@PathParam("id") id: String,
               @PathParam("resource") resource: String,
               @PathParam("subresource") subresource: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"CREATE a sub-resource $resource of main type " +
                  s"$subresource: $id")
        //storageCreate()
        /*
        val mainProtoClass = MidonetMediaTypes.resourceToZoom(mainRes)

        try {
            Await.result(backend.store.get(mainProtoClass, id), 3.seconds)
        } catch {
            case e: NotFoundException =>
                return Response.status(HttpStatus.NOT_FOUND_404)
                               .`type`(mainRes).build()
            case _: Throwable =>
                return Response.serverError().`type`(mainRes).build()
        }

        val subDtoClass = MidonetMediaTypes.resourceToDto(subRes)
        val subZoomClass = MidonetMediaTypes.resourceToZoom(subRes)
        val dto = deserialize(data, subDtoClass)
        dto match {
            case p: Port => p.setDeviceId(id)
        }

        create(UUID.randomUUID(), dto, subRes, subZoomClass)*/
        //storageList()
        // TODO: createResource(data, resource, id, subresource)
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
    def update(@PathParam("id") id: String,
               @PathParam("resource") resource: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"UPDATE a $contentType with $data")
        val dtoAttr = MidonetMediaTypes.ResourceToDtoAttributes(Seq(resource))
        val dto = deserialize(data, dtoAttr.clazz)
        val obj = renderer.to(dto, dtoAttr.zoom.clazz)
        tryWithResponse {
            backend.store.update(obj)
            Response.ok().build()
        }
        // TODO: updateResource(data, resource, id)
    }

    @DELETE
    @Path("/{resource}/{id}")
    def delete(@PathParam("id") id: String,
               @PathParam("resource") resource: String): Response = {
        log.debug(s"DELETE $resource with id $id")
        val dtoAttr = MidonetMediaTypes.ResourceToDtoAttributes(Seq(resource))
        tryWithResponse {
            backend.store.delete(dtoAttr.zoom.clazz, id)
            Response.ok().build()
        }
        // TODO: deleteResource(resource, id)
    }

    private def deserialize[T <: UriResource](data: String, dtoClass: Class[T])
    : T = {
        // TODO: make this into a provider or something, I had to put it there
        //       because scala doesn't manage to read the types
        ResourceUris.objectMapper.readValue(data, dtoClass)
    }

    private def create(id: String, dto: UriResource, resource: String,
                       zoomClass: Class[_ <: MessageOrBuilder]): Response = {
        tryWithResponse {
            dto.setId(id)
            backend.store.create(ZoomConvert.toProto(dto, zoomClass))
            Response.created(uriFor(resource, id)).build()
        }
    }

    private def getResource(args: String*): UriResource =
        listResource(args: _*).last

    private def listResource(args: String*): Seq[UriResource] = {
        if (args.length == 0) {
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }

        var index = 0
        var parentId: String = null
        var dtoAttr: DtoAttribute = null
        var list: Seq[MessageOrBuilder] = null
        var path = List[String]()
        do {
            val resource = args(index)
            val id = if (index + 1 < args.length) args(index + 1) else null
            path = path :+ resource

            // Get the resource attribute.
            dtoAttr = MidonetMediaTypes.ResourceToDtoAttributes(path)
            if (dtoAttr eq null) {
                throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
            }

            if (index + 2 < args.length) {
                val obj = if (list eq null) {
                    Await.result(backend.store.get(dtoAttr.zoom.clazz, id), timeout)
                } else {
                    list.find(getMessageId(_, dtoAttr) == id) match {
                        case Some(o) => o
                        case None => throw new WebApplicationException(Status.NOT_FOUND)
                    }
                }
                parentId = getMessageId(obj, dtoAttr)
                list = listSubresource(
                    obj, dtoAttr, args(index + 2),
                    if (index + 3 < args.length) args(index + 3) else null)
            } else if (list eq null) {
                list = if (id eq null)
                    Await.result(backend.store.getAll(dtoAttr.zoom.clazz), timeout)
                else
                    Seq(Await.result(backend.store.get(dtoAttr.zoom.clazz, id), timeout))
            }
            index += 2
        } while (index < args.length)

        list.map(renderer.from(_, dtoAttr.clazz, parentId, uriInfo.getBaseUri))
    }

    private def listSubresource(obj: MessageOrBuilder, dtoAttr: DtoAttribute,
                                subresource: String, id: String)
    : Seq[MessageOrBuilder] = {
        val fieldAttr = dtoAttr.subresources(subresource)
        val fieldDescriptor =
            getMessageDescriptor(dtoAttr).findFieldByName(fieldAttr.zoom.name)
        if (fieldDescriptor.isRepeated) {
            if (fieldDescriptor.getMessageType.getFullName ==
                classOf[Commons.UUID].getCanonicalName) {
                // The inner list is referenced via the UUIDs.
                if (id eq null) {
                    val ids = obj.getField(fieldDescriptor)
                        .asInstanceOf[JList[Commons.UUID]].asScala
                        .map(_.asJava)
                    Await.result(backend.store.getAll(dtoAttr.zoom.clazz, ids),
                                 timeout)
                }
                else
                    Seq(Await.result(backend.store.get(dtoAttr.zoom.clazz, id),
                                     timeout))
            } else {
                // The inner list is embedded.
                obj.getField(fieldDescriptor)
                    .asInstanceOf[JList[MessageOrBuilder]].asScala
            }
        } else throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
    }

    private def createResource(data: String, args: String*): Response = {
        if (args.length == 0) {
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }

        var index = 0
        var parentId: String = null
        var dtoAttr: DtoAttribute = null
        var list: Seq[MessageOrBuilder] = null
        var path = List[String]()
        do {
            val resource = args(index)
            val id = if (index + 1 < args.length) args(index + 1) else null
            path = path :+ resource

            // Get the resource attribute.
            dtoAttr = MidonetMediaTypes.ResourceToDtoAttributes(path)
            if (dtoAttr eq null) {
                throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
            }

            if (index + 2 < args.length) {
                val obj = if (list eq null) {
                    Await.result(backend.store.get(dtoAttr.zoom.clazz, id), timeout)
                } else {
                    list.find(getMessageId(_, dtoAttr) == id) match {
                        case Some(o) => o
                        case None => throw new WebApplicationException(Status.NOT_FOUND)
                    }
                }
                parentId = getMessageId(obj, dtoAttr)
                list = listSubresource(
                    obj, dtoAttr, args(index + 2),
                    if (index + 3 < args.length) args(index + 3) else null)
            } else if (list eq null) {
                list = if (id eq null)
                    Await.result(backend.store.getAll(dtoAttr.zoom.clazz), timeout)
                else
                    Seq(Await.result(backend.store.get(dtoAttr.zoom.clazz, id), timeout))
            }
            index += 2
        } while (index < args.length)
    }

    private def updateResource(resource: String, id: String, data: String)
    : Response =
        updateResource(Seq((resource, id)), data)

    private def updateResource(resources: Seq[(String, String)], data: String)
    : Response = {
        ???
    }

    private def deleteResource(resource: String, id: String): Response =
        deleteResource(Seq((resource, id)))

    private def deleteResource(resources: Seq[(String, String)]): Response = {
        ???
    }

    private def uriFor(resource: String, id: String): URI = {
        UriBuilder.fromUri(uriInfo.getBaseUri)
                  .segment(resource)
                  .segment(id).build() // TODO: use path and so on
    }

    private def tryWithResponse(f: => Response)(implicit res: String)
    : Response = {
        try {
            f
        } catch {
            case e: NotFoundException =>
                Response.status(HttpStatus.NOT_FOUND_404).`type`(res).build()
            case e: ObjectReferencedException =>
                Response.status(HttpStatus.NOT_ACCEPTABLE_406).`type`(res).build()
            case e: ReferenceConflictException =>
                Response.status(HttpStatus.CONFLICT_409).`type`(res).build()
            case e: ObjectExistsException =>
                Response.status(HttpStatus.CONFLICT_409).`type`(res).build()
        }
    }
}
