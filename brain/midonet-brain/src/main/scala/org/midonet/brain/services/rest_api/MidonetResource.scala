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

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import com.google.common.util.concurrent.MoreExecutors._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.MessageOrBuilder

import org.codehaus.jackson.map.JsonMappingException
import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.brain.rest_api.auth.Token
import org.midonet.brain.services.rest_api.MidonetMediaTypes._
import org.midonet.brain.services.rest_api.models.{ResourceUris, UriResource}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException, ObjectReferencedException, ReferenceConflictException}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend

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
    def get(@PathParam("resource") resource: String,
            @PathParam("id") id: String,
            @HeaderParam("accept") accept: String) = {
        log.debug(s"GET /$resource/$id for media type $accept")
        getResource(resource, id)
    }

    @GET
    @Path("/{resource1}/{id1}/{resource2}/{id2}")
    def get(@PathParam("resource1") resource1: String,
            @PathParam("resource2") resource2: String,
            @PathParam("id1") id1: String,
            @PathParam("id2") id2: String,
            @HeaderParam("accept") accept: String) = {
        log.debug(s"GET /$resource1/$id1/$resource2/$id2 for media type " +
                  s"$accept")
        getResource(resource1, id1, resource2, id2)
    }

    @GET
    @Path("/{resource1}/{id1}/{resource2}/{id2}/{resource3}/{id3}")
    def get(@PathParam("resource1") resource1: String,
            @PathParam("resource2") resource2: String,
            @PathParam("resource3") resource3: String,
            @PathParam("id1") id1: String,
            @PathParam("id2") id2: String,
            @PathParam("id3") id3: String,
            @HeaderParam("accept") accept: String) = {
        log.debug(s"GET /$resource1/$id1/$resource2/$id2/$resource3/$id3 for " +
                  s"media type $accept")
        getResource(resource1, id1, resource2, id2, resource3, id3)
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
             @HeaderParam("Accept") accept: String): util.List[UriResource] = {
        log.info(s"LIST /$resource for media type $accept")
        listResource(resource).asJava
    }

    @GET
    @Path("/{resource1}/{id1}/{resource2}")
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
    def list(@PathParam("resource1") resource1: String,
             @PathParam("resource2") resource2: String,
             @PathParam("id1") id1: String,
             @HeaderParam("Accept") accept: String)
    : util.List[UriResource] = {
        log.info(s"LIST /$resource1/$id1/$resource2 for media type $accept")
        listResource(resource1, id1, resource2).asJava
    }

    @GET
    @Path("/{resource1}/{id1}/{resource2}/{id2}/{resource3}")
    @Produces(Array(APPLICATION_DHCP_HOST_COLLECTION_JSON,
                    APPLICATION_DHCP_HOST_COLLECTION_JSON_V2,
                    APPLICATION_DHCPV6_HOST_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON))
    def list(@PathParam("resource1") resource1: String,
             @PathParam("resource2") resource2: String,
             @PathParam("resource3") resource3: String,
             @PathParam("id1") id1: String,
             @PathParam("id2") id2: String,
             @HeaderParam("Accept") accept: String)
    : util.List[UriResource] = {
        log.info(s"LIST /$resource1/$id1/$resource2/$id2/$resource3 for " +
                 s"media type $accept")
        listResource(resource1, id1, resource2, id2, resource3).asJava
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
        log.debug(s"CREATE /$resource for media type $contentType")
        createResource(data, resource)
    }

    @POST
    @Path("/{resource1}/{id1}/{resource2}")
    @Consumes(Array(APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_DHCPV6_SUBNET_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_RULE_JSON,
                    APPLICATION_RULE_JSON_V2,
                    APPLICATION_TUNNEL_ZONE_HOST_JSON,
                    MediaType.APPLICATION_JSON))
    def create(@PathParam("resource1") resource1: String,
               @PathParam("resource2") resource2: String,
               @PathParam("id1") id1: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"CREATE /$resource1/$id1/$resource2 for media type " +
                  s"$contentType")
        createResource(data, resource1, id1, resource2)
    }

    @POST
    @Path("/{resource1}/{id1}/{resource2}/{id2}/{resource3}")
    @Consumes(Array(APPLICATION_DHCP_HOST_JSON,
                    APPLICATION_DHCP_HOST_JSON_V2,
                    APPLICATION_DHCPV6_HOST_JSON))
    def create(@PathParam("resource1") resource1: String,
               @PathParam("resource2") resource2: String,
               @PathParam("resource3") resource3: String,
               @PathParam("id1") id1: String,
               @PathParam("id2") id2: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"CREATE /$resource1/$id1/$resource2/$id2/$resource3 for " +
                  s"media type $contentType")
        createResource(data, resource1, id1, resource2, id2, resource3)
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
    def update(@PathParam("resource") resource: String,
               @PathParam("id") id: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"UPDTAE /$resource for media type $contentType")
        updateResource(data, resource, id)
    }

    @DELETE
    @Path("/{resource}/{id}")
    def delete(@PathParam("resource") resource: String,
               @PathParam("id") id: String): Response = {
        log.debug(s"DELETE /$resource/$id")
        deleteResource(resource, id)
    }

    @DELETE
    @Path("/{resource1}/{id1}/{resource2}/{id2}")
    def delete(@PathParam("resource1") resource1: String,
               @PathParam("resource2") resource2: String,
               @PathParam("id1") id1: String,
               @PathParam("id2") id2: String): Response = {
        log.debug(s"DELETE /$resource1/$id1/$resource2/$id2")
        deleteResource(resource1, id1, resource2, id2)
    }

    @DELETE
    @Path("/{resource1}/{id1}/{resource2}/{id2}/{resource3}/{id3}")
    def delete(@PathParam("resource1") resource1: String,
               @PathParam("resource2") resource2: String,
               @PathParam("resource3") resource3: String,
               @PathParam("id1") id1: String,
               @PathParam("id2") id2: String,
               @PathParam("id3") id3: String): Response = {
        log.debug(s"DELETE /$resource1/$id1/$resource2/$id2/$resource3/$id3")
        deleteResource(resource1, id1, resource2, id2, resource3, id3)
    }

    private def makeRequestPath(args: Seq[String]): RequestPath = {
        var path = List[String]()
        val items = for (index <- 0 until args.length by 2)
            yield {
                path = path :+ args(index)
                val dtoAttr = ResourceAttributes(path)
                val id = if (args.isDefinedAt(index + 1))
                    ResId(args(index + 1), dtoAttr.getProtoId(args(index + 1)))
                else null
                RequestItem(dtoAttr, id)
            }
        RequestPath(items)
    }

    private def getResource(args: String*): UriResource = {
        listResource(args: _*).lastOption getOrElse {
            throw new WebApplicationException(Status.NOT_FOUND)
        }
    }

    @throws[WebApplicationException]
    private def listResource(args: String*): Seq[UriResource] = {
        if (args.length == 0) {
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }

        val path = makeRequestPath(args)
        val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
        var parentId: String = null
        var list: Seq[MessageOrBuilder] = null

        for (index <- 0 until path.length - 1) {
            val obj: MessageOrBuilder = if (list eq null) tryWithException {
                storeGet(path(index))
            } else {
                list.findByItem(path(index))
                    .getOrElse(throw new WebApplicationException(Status.NOT_FOUND))
            }
            uri.segment(path(index).name, path(index).id.id)
            parentId = path(index).id.id
            list = listSubresource(obj, path, index + 1)
        }

        if (list eq null) {
            list = if (path.last.hasId) tryWithException {
                storeGet(path.last).toSeq
            } else {
                storeGetAll(path.last)
            }
        }

        // Convert the list elements to the corresponding DTO, and initialize
        // the fields: base URI, parent ID and ownership.
        list.map(obj => {
            val dto = ZoomConvert.fromProto(obj, path.last.attr.clazz)
            dto.setBaseUri(uri.build())
            path.last.attr.setParentId(dto, parentId)
            if (path.last.attr.ownership ne null) tryWithException {
                val owners = Await.result(backend.ownershipStore.getOwners(
                    path.last.attr.zoom.clazz, dto.getId), timeout)
                path.last.attr.setOwners(dto, owners)
            }
            dto
        })
    }

    @throws[WebApplicationException]
    private def listSubresource(obj: MessageOrBuilder, path: RequestPath,
                                index: Int): Seq[MessageOrBuilder] = {
        if (path.isSubresourceReferenced(index)) {
            // The inner list is referenced via the UUIDs.
            if (path(index).hasId) tryWithException {
                storeGet(path(index)).toSeq
            } else {
                storeGetAll(path(index), obj.getSubresourceIds(path, index))
            }
        } else if (path.isSubresourceEmbedded(index)) {
            // The inner list is embedded.
            obj.getSubresources(path, index)
        } else throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
    }

    private def createResource(data: String, args: String*): Response = {
        if (args.length == 0 || args.length % 2 == 0) {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build()
        }

        val path = makeRequestPath(args)

        // Create the child resource from the posted data.
        val dto = try {
            ResourceUris.objectMapper.readValue(data, path.last.attr.clazz)
        } catch {
            case e: JsonMappingException =>
                return Response.status(HttpStatus.BAD_REQUEST_400).build()
        }

        path.last.attr.generateId(dto)
        if (path.length > 1) {
            path.last.attr.setParentId(dto, path(path.length - 2).id.id)
        }
        var obj = ZoomConvert.toProto(dto, path.last.attr.zoom.clazz)

        tryWithResponse {
            val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
                .segment(args: _*).segment(dto.getId)
                .build()
            obj = createSubresource(path, obj)

            // Update all parent resources that are affected by the creation.
            var index = path.length - 2
            while (obj ne null) {
                obj = updateSubresource(path, index, obj)
                index -= 1
            }

            Response.created(uri).build()
        }
    }

    private def createSubresource(path: RequestPath, obj: MessageOrBuilder)
    : MessageOrBuilder = {
        val protoId = path.last.attr.getProtoId(obj)
        val id = ResId(path.last.attr.getDtoId(protoId), protoId)
        if (path.isSubresourceReferenced(path.length - 1)) {

            // TODO: Find an alternative solution

            obj match {
                case port: Topology.Port =>
                    if (Await.result(backend.store.exists(classOf[Topology.Port],
                                                          port.getId), timeout)) {
                        var p = Await.result(backend.store.get(classOf[Topology.Port],
                                                               port.getId), timeout)
                        p = p.toBuilder.setHostId(port.getHostId)
                                       .setInterfaceName(port.getInterfaceName)
                                       .build()
                        backend.store.update(p)
                        return null
                    }
                case _ =>
            }

            // TODO: End

            // The subresource is referenced: create the resource, and return
            // the referencing resource if modified.
            storeCreate(path.last, obj)
            val parent = storeGet(path(path.length - 2))
            parent.addSubresourceId(path, path.length - 1, id)
        } else if (path.isSubresourceEmbedded(path.length - 1)) {
            // The resource is embedded: add the resource to the parent, and
            // return the modified parent.
            val parent = storeGet(path(path.length - 2))
            parent.addSubresource(path, path.length - 1, obj)
        } else {
            // The resource is neither referenced, nor embedded: only create the
            // resource.
            storeCreate(path.last, obj)
            null
        }
    }

    private def updateResource(data: String, args: String*): Response = {
        ???
    }

    /**
     * Updates the resource at the specified index on the given request path,
     * and it returns the parent resource, if it was modified by the update.
     */
    private def updateSubresource(path: RequestPath, index: Int,
                                  obj: MessageOrBuilder): MessageOrBuilder = {
        if (path.isSubresourceEmbedded(index)) {
            // The resource is embedded: update the resource in the parent, and
            // return the modified parent.
            val parent = storeGet(path(index - 1))
            parent.updateSubresource(path, index, obj)
        } else {
            // The resource is either a referenced or top resource: only update
            // the resource.
            storeUpdate(path(index), obj)
            null
        }
    }

    private def deleteResource(args: String*): Response = {
        if (args.length == 0 || args.length % 2 == 1) {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build()
        }

        val path = makeRequestPath(args)

        tryWithResponse {
            // Delete the sub-resource.
            var obj = deleteSubresource(path)

            // Update all parent resources that are affected by the deletion.
            var index = path.length - 2
            while (obj ne null) {
                obj = updateSubresource(path, index, obj)
                index -= 1
            }

            Response.ok().build()
        }
    }

    /**
     * Deletes the last resource from the given request path, and it returns
     * its the parent resource, if it was modified by the deletion.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    private def deleteSubresource(path: RequestPath): MessageOrBuilder = {
        val resource = path.last
        if (path.isSubresourceReferenced(path.length - 1)) {
            // The resource is referenced: delete the resource, and return
            // the referencing resource if modified.
            storeDelete(path.last)
            val parent = storeGet(path(path.length - 2))
            parent.deleteSubresourceId(path, path.length - 1, resource.id)
        } else if (path.isSubresourceEmbedded(path.length - 1)) {
            // The resource is embedded: remove the resource in the parent, and
            // return the modified parent.
            val parent = storeGet(path(path.length - 2))
            parent.deleteSubresource(path, path.length - 1, resource.id)
        } else {
            // The resource is neither referenced, nor embedded: only delete the
            // resource.
            storeDelete(path.last)
            null
        }
    }

    @inline
    @throws[NotFoundException]
    private def storeGet(item: RequestItem): MessageOrBuilder = {
        Await.result(backend.store.get(item.attr.zoom.clazz, item.id.protoId),
                     timeout)
    }

    @inline
    private def storeGetAll(item: RequestItem): Seq[MessageOrBuilder] = {
        Await.result(backend.store.getAll(item.attr.zoom.clazz), timeout)
    }

    @inline
    @throws[NotFoundException]
    private def storeGetAll(item: RequestItem, ids: Set[ResId])
    : Seq[MessageOrBuilder] = {
        Await.result(backend.store.getAll(item.attr.zoom.clazz,
                                          ids.map(_.protoId).toSeq),
                     timeout)
    }

    @inline
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    private def storeCreate(item: RequestItem, obj: MessageOrBuilder): Unit = {
        if (item.attr.isStorable) {
            backend.store.create(obj)
        }
    }

    @inline
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    private def storeUpdate(item: RequestItem, obj: MessageOrBuilder): Unit = {
        if ((obj ne null) && item.attr.isStorable) {
            backend.store.update(obj)
        }
    }

    @inline
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    private def storeDelete(item: RequestItem): Unit = {
        if (item.attr.isStorable) {
            backend.store.delete(item.attr.zoom.clazz, item.id.protoId)
        }
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
