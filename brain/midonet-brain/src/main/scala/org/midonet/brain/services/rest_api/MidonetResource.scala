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

import java.util
import java.util.{List => JList, UUID}

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

import com.google.common.util.concurrent.MoreExecutors._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.MessageOrBuilder

import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.brain.rest_api.auth.Token
import org.midonet.brain.services.rest_api.MidonetMediaTypes._
import org.midonet.brain.services.rest_api.models.{ResourceUris, UriResource}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{ObjectExistsException, ReferenceConflictException, ObjectReferencedException, NotFoundException}
import org.midonet.cluster.models.{Topology, Commons}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._

@Path("/")
@RequestScoped
class MidonetResource @Inject()(implicit backend: MidonetBackend) {

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

    @POST
    @Path("/login")
    @Produces(Array(APPLICATION_TOKEN_JSON,
                    MediaType.APPLICATION_JSON))
    def login() = {
        Response.ok(new Token("999888777666", null))
            .cookie(new NewCookie("sessionId", "999888777666"))
            .build()
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
    @Path("/{resource}/{id}/{subresource}/{sid}/{subsubresource}/{ssid}")
    def get(@PathParam("id") id: String,
            @PathParam("sid") sid: String,
            @PathParam("ssid") ssid: String,
            @PathParam("resource") resource: String,
            @PathParam("subresource") subresource: String,
            @PathParam("subsubresource") subsubresource: String,
            @HeaderParam("accept") acceptMedia: String) = {
        log.debug(s"GET $resource with id $id for media type $acceptMedia")
        getResource(resource, id, subresource, sid, subsubresource, ssid)
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
    @Produces(Array(APPLICATION_DHCP_HOST_COLLECTION_JSON,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON_V2,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON,
                    APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2,
                    APPLICATION_DHCPV6_HOST_COLLECTION_JSON,
                    APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    APPLICATION_INTERFACE_COLLECTION_JSON,
                    APPLICATION_IP4_MAC_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2,
                    APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_ROUTE_COLLECTION_JSON,
                    APPLICATION_RULE_COLLECTION_JSON,
                    APPLICATION_RULE_COLLECTION_JSON_V2,
                    APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON))
    def list(@PathParam("id") id: String,
             @PathParam("resource") resource: String,
             @PathParam("subresource") subresource: String,
             @HeaderParam("Accept") accepts: String)
    : util.List[UriResource] = {
        log.debug(s"LIST: $accepts")
        listResource(resource, id, subresource).asJava
    }

    @GET
    @Path("/{resource}/{id}/{subresource}/{sid}/{subsubresource}")
    @Produces(Array(APPLICATION_DHCP_HOST_COLLECTION_JSON,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON_V2,
                    APPLICATION_DHCPV6_HOST_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON))
    def list(@PathParam("id") id: String,
             @PathParam("sid") sid: String,
             @PathParam("resource") resource: String,
             @PathParam("subresource") subresource: String,
             @PathParam("subsubresource") subsubresource: String,
             @HeaderParam("Accept") accepts: String)
    : util.List[UriResource] = {
        log.debug(s"LIST: $accepts")
        listResource(resource, id, subresource, sid, subsubresource).asJava
    }

    @POST
    @Path("/{resource}$")
    @Consumes(Array(APPLICATION_AD_ROUTE_JSON,
                    APPLICATION_BGP_JSON,
                    APPLICATION_BRIDGE_JSON,
                    APPLICATION_BRIDGE_JSON_V2,
                    APPLICATION_BRIDGE_JSON_V3,
                    APPLICATION_CHAIN_JSON,
                    APPLICATION_HEALTH_MONITOR_JSON,
                    APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_IP_ADDR_GROUP_JSON,
                    APPLICATION_LOAD_BALANCER_JSON,
                    APPLICATION_POOL_MEMBER_JSON,
                    APPLICATION_POOL_JSON,
                    APPLICATION_PORT_LINK_JSON,
                    APPLICATION_PORTGROUP_JSON,
                    APPLICATION_PORTGROUP_PORT_JSON,
                    APPLICATION_ROUTER_JSON,
                    APPLICATION_ROUTER_JSON_V2,
                    APPLICATION_RULE_JSON,
                    APPLICATION_RULE_JSON_V2,
                    APPLICATION_TENANT_JSON,
                    APPLICATION_TRACE_REQUEST_JSON,
                    APPLICATION_TUNNEL_ZONE_JSON,
                    APPLICATION_VIP_JSON,
                    APPLICATION_VTEP_JSON,
                    MediaType.APPLICATION_JSON))
    def create(@HeaderParam("Content-Type") contentType: String,
               @PathParam("resource") resource: String,
               data: String): Response = {
        log.debug(s"CREATE a $contentType")
        createResource(data, resource)
    }

    @POST
    @Path("/{resource}/{id}/{subresource}")
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_DHCPV6_SUBNET_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_TUNNEL_ZONE_HOST_JSON,
                    MediaType.APPLICATION_JSON))
    def create(@PathParam("id") id: String,
               @PathParam("resource") resource: String,
               @PathParam("subresource") subresource: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"CREATE a sub-resource $subresource of main type " +
                  s"$resource: $id")
        createResource(data, resource, id, subresource)
    }

    @POST
    @Path("/{resource}/{id}/{subresource}/{sid}/{subsubresource}")
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON,
                    APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_DHCPV6_HOST_JSON))
    def create(@PathParam("id") id: String,
               @PathParam("sid") sid: String,
               @PathParam("resource") resource: String,
               @PathParam("subresource") subresource: String,
               @PathParam("subsubresource") subsubresource: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"CREATE a sub-sub-resource $subsubresource of main type " +
                  s"$resource/$subresource: $id/$sid")
        createResource(data, resource, id, subresource, sid, subsubresource)
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
        updateResource(data, resource, id)
    }

    @DELETE
    @Path("/{resource}/{id}")
    def delete(@PathParam("id") id: String,
               @PathParam("resource") resource: String): Response = {
        log.debug(s"DELETE $resource with id $id")
        val dtoAttr = MidonetMediaTypes.ResourceAttributes(Seq(resource))
        tryWithResponse {
            backend.store.delete(dtoAttr.zoom.clazz, id)
            Response.ok().build()
        }
        // TODO: deleteResource(resource, id)
    }

    private def getResource(args: String*): UriResource = {
        listResource(args: _*).lastOption getOrElse {
            throw new WebApplicationException(Status.NOT_FOUND)
        }
    }

    private def listResource(args: String*): Seq[UriResource] = {
        if (args.length == 0) {
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }

        var index = 0
        var parentId: String = null
        var dtoAttr: DtoAttribute = null
        var list: Seq[MessageOrBuilder] = null
        var path = List[String]()
        val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
        do {
            val resource = args(index)
            val id = if (index + 1 < args.length) args(index + 1) else null
            path = path :+ resource

            // Get the resource attribute.
            dtoAttr = MidonetMediaTypes.ResourceAttributes(path)
            if (dtoAttr eq null) {
                throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
            }

            if (index + 2 < args.length) {
                val obj = if (list eq null) tryWithException {
                    Await.result(backend.store.get(dtoAttr.zoom.clazz,
                                                   dtoAttr.getProtoId(id)),
                                 timeout)
                } else {
                    list.find(dtoAttr.getId(_) == dtoAttr.getProtoId(id)) match {
                        case Some(o) => o
                        case None => throw new WebApplicationException(Status.NOT_FOUND)
                    }
                }
                uri.segment(resource, id)
                parentId = dtoAttr.getId(obj)
                list = listSubresource(
                    obj, dtoAttr, path :+ args(index + 2),
                    if (index + 3 < args.length) args(index + 3) else null)
            } else if (list eq null) {
                list = if (id eq null)
                    Await.result(backend.store.getAll(dtoAttr.zoom.clazz),
                                 timeout)
                else tryWithException {
                    Seq(Await.result(backend.store.get(dtoAttr.zoom.clazz, id),
                                     timeout))
                }
            }
            index += 2
        } while (index < args.length)
        // Convert the list elements to the corresponding DTO, and initialize
        // the fields: base URI, parent ID and ownership.
        list.map(obj => {
            val dto = ZoomConvert.fromProto(obj, dtoAttr.clazz)
            dto.setBaseUri(uri.build())
            dtoAttr.setParentId(dto, parentId)
            if (dtoAttr.ownership ne null) tryWithException {
                val owners = Await.result(backend.ownershipStore.getOwners(
                    dtoAttr.zoom.clazz, dto.getId), timeout)
                dtoAttr.setOwners(dto, owners)
            }
            dto
        })
    }

    @throws[WebApplicationException]
    private def listSubresource(obj: MessageOrBuilder, dtoAttr: DtoAttribute,
                                path: DtoPath, id: String)
    : Seq[MessageOrBuilder] = {
        val fieldDescriptor = dtoAttr.getSubresourceDescriptor(path.last)
        if (fieldDescriptor.isRepeated) {
            if (fieldDescriptor.getMessageType == Commons.UUID.getDescriptor) {
                val subAttr = MidonetMediaTypes.ResourceAttributes(path)
                // The inner list is referenced via the UUIDs.
                if (id eq null) {
                    val ids = obj.getField(fieldDescriptor)
                        .asInstanceOf[JList[Commons.UUID]].asScala
                    Await.result(backend.store.getAll(subAttr.zoom.clazz, ids),
                                 timeout)
                }
                else tryWithException {
                    Seq(Await.result(backend.store.get(subAttr.zoom.clazz,
                                                       subAttr.getProtoId(id)),
                                     timeout))
                }
            } else {
                // The inner list is embedded.
                obj.getField(fieldDescriptor)
                    .asInstanceOf[JList[MessageOrBuilder]].asScala
            }
        } else throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
    }

    private def createResource(data: String, args: String*): Response = {
        if (args.length == 0 || args.length % 2 == 0) {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build()
        }

        val resources = for (index <- 0 until args.length by 2) yield args(index)
        val ids = for (index <- 1 until args.length by 2) yield args(index)
        var index = ids.length
        var dtoAttr: DtoAttribute = null
        var dto: UriResource = null
        var obj: MessageOrBuilder = null
        var path = resources
        var response: Response = null

        do {
            // Get the resource attribute
            dtoAttr = MidonetMediaTypes.ResourceAttributes(path)
            if (index == ids.length) {
                // Create the child resource from the data in the backend store.
                dto = ResourceUris.objectMapper.readValue(data, dtoAttr.clazz)
                dtoAttr.generateId(dto)
                if (index > 0) {
                    dtoAttr.setParentId(dto, ids(index - 1))
                }
                obj = ZoomConvert.toProto(dto, dtoAttr.zoom.clazz)
                response = tryWithResponse {
                    if (dtoAttr.isStorable) {
                        backend.store.create(obj)
                    }
                    val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
                                        .segment(args: _*).segment(dto.getId)
                                        .build()
                    Response.created(uri).build()
                }
            } else {
                // Add the child resource to its parent resource and update
                // the parent resource in the backend store.
                response = tryWithResponse {
                    obj = createSubresource(path, ids(index),
                                            resources(index + 1), obj)
                    if ((obj ne null) && dtoAttr.isStorable) {
                        backend.store.update(obj)
                    }
                    response
                }
            }

            path = path.dropRight(1)
            index -= 1
        } while (index >= 0 && (obj ne null))
        response
    }

    private def createSubresource(path: DtoPath, id: String,
                                  subresource: String, child: MessageOrBuilder)
    : MessageOrBuilder = {
        val parentAttr = MidonetMediaTypes.ResourceAttributes(path)
        val childAttr = MidonetMediaTypes.ResourceAttributes(path :+ subresource)
        val fieldDescriptor = parentAttr.getSubresourceDescriptor(subresource)
        val parent = Await.result(backend.store.get(parentAttr.zoom.clazz,
                                                    parentAttr.getProtoId(id)),
                                  timeout)
        if (fieldDescriptor.isRepeated) {
            if (fieldDescriptor.getMessageType == Commons.UUID.getDescriptor) {
                // The child object is referenced via a list of UUIDs.
                val childId = childAttr.getProtoId(child)
                val referencingIds = parent.getField(fieldDescriptor)
                    .asInstanceOf[JList[Commons.UUID]].asScala
                    .map(_.asJava.toString)

                if (referencingIds.contains(childId)) return null
                parentAttr.newBuilder(parent)
                          .addRepeatedField(fieldDescriptor, childId)
                          .build()
            } else {
                // Validate that a child with the same ID does not exist.
                if (childAttr.hasProtoId(child)) {
                    // The child object is embedded in a list of messages.
                    val childId = childAttr.getProtoId(child)
                    val childrenIds = parent.getField(fieldDescriptor)
                        .asInstanceOf[JList[MessageOrBuilder]]
                        .asScala.map(childAttr.getProtoId)
                    if (childrenIds.contains(childId)) {
                        throw new WebApplicationException(Status.CONFLICT)
                    }
                }
                val builder = parentAttr
                    .newBuilder(parent)
                    .addRepeatedField(fieldDescriptor, child)

                // TODO: Add the custom setter to different class.
                if (parent.getClass == classOf[Topology.TunnelZone]) {
                    val childFd = childAttr.getFieldDescriptor("host_id")
                    val parentFd = parentAttr.getFieldDescriptor("host_ids")
                    val id = child.getField(childFd)
                    builder.addRepeatedField(parentFd, id)
                }

                builder.build()
            }
        } else throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
    }

    private def updateResource(data: String, args: String*): Response = {
        ???
    }

    private def updateSubresource(path: DtoPath, id: String,
                                  subresource: String, child: MessageOrBuilder)
    : MessageOrBuilder = {
        val parentAttr = MidonetMediaTypes.ResourceAttributes(path)
        val childAttr = MidonetMediaTypes.ResourceAttributes(path :+ subresource)
        val fieldDescriptor = parentAttr.getSubresourceDescriptor(subresource)
        val parent = Await.result(backend.store.get(parentAttr.zoom.clazz, id),
                                  timeout)
        if (fieldDescriptor.isRepeated) {
            if (fieldDescriptor.getMessageType == Commons.UUID.getDescriptor) {
                // The child object is referenced via a list of UUIDs.
                val childId = childAttr.getId(child)
                val referencingIds = parent.getField(fieldDescriptor)
                    .asInstanceOf[JList[Commons.UUID]].asScala
                    .map(_.asJava.toString)

                if (referencingIds.contains(childId)) return null
                parentAttr.newBuilder(parent)
                    .addRepeatedField(fieldDescriptor,
                                      UUID.fromString(childId).asProto)
                    .build()
            } else {
                // The child object is embedded in a list of messages.
                //val childId = childAttr.getId(child)
                //val children = parent.getField(fieldDescriptor)
                //                     .asInstanceOf[JList[MessageOrBuilder]]
                //                     .asScala

                parentAttr.newBuilder(parent)
                    .addRepeatedField(fieldDescriptor, child)
                    .build()
            }
        } else throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
    }

    private def deleteResource(args: String*): Response = {
        ???
    }

    private def tryWithException[T](f: => T): T = {
        try {
            f
        } catch {
            case e: NotFoundException =>
                throw new WebApplicationException(Status.NOT_FOUND)
            case e: ObjectReferencedException =>
                throw new WebApplicationException(Status.NOT_ACCEPTABLE)
            case e: ReferenceConflictException =>
                throw new WebApplicationException(Status.CONFLICT)
            case e: ObjectExistsException =>
                throw new WebApplicationException(Status.CONFLICT)
        }
    }

    private def tryWithResponse[R](f: => Response): Response = {
        try {
            f
        } catch {
            case e: NotFoundException =>
                val i = 0
                Response.status(HttpStatus.NOT_FOUND_404).build()
            case e: ObjectReferencedException =>
                Response.status(HttpStatus.NOT_ACCEPTABLE_406).build()
            case e: ReferenceConflictException =>
                Response.status(HttpStatus.CONFLICT_409).build()
            case e: ObjectExistsException =>
                Response.status(HttpStatus.CONFLICT_409).build()
        }
    }
}
