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

import java.util
import java.util.ConcurrentModificationException

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import com.google.common.util.concurrent.MoreExecutors._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import org.codehaus.jackson.map.JsonMappingException
import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException, ObjectReferencedException, ReferenceConflictException}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.models
import org.midonet.cluster.rest_api.models.{HostInterfacePort, ResourceUris, UriResource}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.DtoRenderer._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@Path("/")
@RequestScoped
class MidonetResource @Inject()(implicit backend: MidonetBackend) {

    private implicit val ec =
        ExecutionContext.fromExecutor(sameThreadExecutor())
    private final val log =
        Logger(LoggerFactory.getLogger(classOf[MidonetResource]))
    private final val timeout = 5 seconds
    private final val storageAttempts = 3

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
             @HeaderParam("Accept") accept: String): util.List[UriResource[_]] = {
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
    : util.List[UriResource[_]] = {
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
    : util.List[UriResource[_]] = {
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
                    APPLICATION_ROUTE_JSON,
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
    @Consumes(Array(APPLICATION_AD_ROUTE_JSON,
                    APPLICATION_BGP_JSON,
                    APPLICATION_BRIDGE_JSON,
                    APPLICATION_BRIDGE_JSON_V2,
                    APPLICATION_BRIDGE_JSON_V3,
                    APPLICATION_CHAIN_JSON,
                    APPLICATION_DHCP_SUBNET_JSON,
                    APPLICATION_DHCP_SUBNET_JSON_V2,
                    APPLICATION_DHCPV6_SUBNET_JSON,
                    APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    APPLICATION_HEALTH_MONITOR_JSON,
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    APPLICATION_HOST_JSON_V2,
                    APPLICATION_HOST_JSON_V3,
                    APPLICATION_IP_ADDR_GROUP_JSON,
                    APPLICATION_LOAD_BALANCER_JSON,
                    APPLICATION_POOL_MEMBER_JSON,
                    APPLICATION_POOL_JSON,
                    APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
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
    def update(@PathParam("resource") resource: String,
               @PathParam("id") id: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"UPDATE /$resource for media type $contentType")
        updateResource(data, resource, id)
    }

    @PUT
    @Path("/{resource1}/{id1}/{resource2}/{id2}")
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    MediaType.APPLICATION_JSON))
    def update(@PathParam("resource1") resource1: String,
               @PathParam("resource2") resource2: String,
               @PathParam("id1") id1: String,
               @PathParam("id2") id2: String,
               @HeaderParam("Content-Type") contentType: String,
               data: String): Response = {
        log.debug(s"UPDATE /$resource1/$id1/$resource2/$id2 for media type " +
                  s"$contentType")
        updateResource(data, resource1, id1, resource2, id2)
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

    /**
     * Creates a [[RequestPath]], which is a list of [[RequestItem]]s from
     * a set of string arguments, ordered as {resource1, id1, resource2, id2,...
     * resourceN, [idN]}. Each request item will contain the [[DtoAttribute]]
     * corresponding to the resource, and the resouurce identifier as a
     * [[ResId]], containing both the API identifier and the corresponding
     * Protocol Buffers identifier.
     */
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

    /**
     * Gets a resource for a given request path.
     */
    private def getResource(args: String*): UriResource[_] = {
        listResource(args: _*).lastOption getOrElse {
            throw new WebApplicationException(Status.NOT_FOUND)
        }
    }

    /**
     * Lists all resources for a given request path.
     */
    @throws[WebApplicationException]
    private def listResource(args: String*): Seq[UriResource[_]] = {
        if (args.length == 0) {
            throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }

        val path = makeRequestPath(args)
        val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
        var parentId: String = null
        var list: Seq[Message] = null

        for (index <- 0 until path.length - 1) {
            val obj: Message = if (list eq null) tryWithException {
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

    /**
     * Lists all subresources of a given object and request path.
     * @param obj The parent object for which to list the subresources.
     * @param path The request path.
     * @param index The index of the subresource in the request path.
     */
    @throws[WebApplicationException]
    private def listSubresource(obj: Message, path: RequestPath,
                                index: Int): Seq[Message] = {
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

    /**
     * Creates a resource from the given data and request path.
     */
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
        var obj: Message = ZoomConvert.toProto(dto, path.last.attr.zoom.clazz)

        tryWithResponse {
            val uri = UriBuilder.fromUri(uriInfo.getBaseUri)
                .segment(args: _*).segment(dto.getId.toString)
                .build()
            obj = createSubresource(path, obj)

            // Update all parent resources that are affected by the creation.
            var index = path.length - 2
            while (obj ne null) {
                obj = updateSubresource(path, index, obj, merge = false)
                index -= 1
            }

            Response.created(uri).build()
        }
    }

    /**
     * Creates a subresource for a given object and request path.
     * @param path The path identifying the subresource, i.e. the subresource
     *             must be the last item in the request path.
     * @param obj The subresource object to create.
     * @return The parent resource, if it was modified by the creation of the
     *         subresource. If the parent is not affected by the creation of
     *         the subresource, the method returns `null`.
     */
    private def createSubresource(path: RequestPath, obj: Message)
    : Message = {
        val protoId = path.last.attr.getProtoId(obj)
        val id = ResId(path.last.attr.getDtoId(protoId), protoId)

        // TODO : Use annotations or custom builder
        if (path.last.attr.clazz == classOf[HostInterfacePort]) {
            var port = storeGet(classOf[Topology.Port], protoId)
            port = port.toBuilder.mergeFrom(obj).build()
            log.info("Port update for binding create: {}/{}\n{}", port.getClass,
                     port.getProtoId, port)
            tryWithRaceProtection { backend.store.update(port) }
            return null
        }
        // TODO : End

        if (path.isSubresourceReferenced(path.length - 1)) {
            // The subresource is referenced: create the resource, and
            // return the referencing resource if modified.
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

    /**
     * Updates a resource from the given data and request path.
     */
    private def updateResource(data: String, args: String*): Response = {
        if (args.length == 0 || args.length % 2 == 1) {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build()
        }

        val path = makeRequestPath(args)

        // Create the child resource from the posted data.
        val dto = try {
            ResourceUris.objectMapper.readValue(data, path.last.attr.clazz)
        } catch {
            case e: JsonMappingException =>
                log.error("Error", e)
                return Response.status(HttpStatus.BAD_REQUEST_400).build()
        }

        var obj = ZoomConvert.toProto(dto, path.last.attr.zoom.clazz)

        tryWithResponse {
            obj = updateSubresource(path, path.length - 1, obj, merge = true)

            // Update all parent resources that are affected by the creation.
            var index = path.length - 2
            while (obj ne null) {
                obj = updateSubresource(path, index, obj, merge = false)
                index -= 1
            }

            Response.ok().build()
        }
    }

    /**
     * Updates the resource at the specified index on the given request path,
     * and it returns the parent resource, if it was modified by the update.
     */
    private def updateSubresource(path: RequestPath, index: Int,
                                  obj: Message, merge: Boolean): Message = {
        // TODO : Use annotation or custom builder
        val o = obj match {
            case port: Topology.Port =>
                val current = storeGet(classOf[Topology.Port], port.getId)
                val builder = port.toBuilder
                if (current.hasHostId)
                    builder.setHostId(current.getHostId)
                if (current.hasInterfaceName)
                    builder.setInterfaceName(current.getInterfaceName)
                builder.build()
            case _ => obj
        }
        // TODO : End

        if (path.isSubresourceEmbedded(index)) {
            // The resource is embedded: update the resource in the parent, and
            // return the modified parent.
            val parent = storeGet(path(index - 1))
            parent.updateSubresource(path, index, o)
        } else {
            // The resource is either a referenced or top resource: only update
            // the resource.
            val current =
                if (merge)
                    o.copySubresources(path, index, storeGet(path(index)))
                else o
            storeUpdate(path(index), current)
            null
        }
    }

    /**
     * Deletes a resource from the given data and request path.
     */
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
                obj = updateSubresource(path, index, obj, merge = false)
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
    private def deleteSubresource(path: RequestPath): Message = {
        val item = path.last

        // TODO : Use annotations or custom builder
        if (item.attr.clazz == classOf[HostInterfacePort]) {
            val p = storeGet(classOf[Topology.Port], item.id.protoId)
            val port = p.toBuilder.clearHostId().clearInterfaceName().build()
            log.info("Port update for binding delete: {}/{}\n{}", port.getClass,
                     port.getProtoId, port)
            tryWithRaceProtection { backend.store.update(port) }
            return null
        }
        // TODO : End

        if (path.isSubresourceReferenced(path.length - 1)) {
            // The resource is referenced: delete the resource, and return
            // the referencing resource if modified.
            storeDelete(path.last)
            val parent = storeGet(path(path.length - 2))
            parent.deleteSubresourceId(path, path.length - 1, item.id)
        } else if (path.isSubresourceEmbedded(path.length - 1)) {
            // The resource is embedded: remove the resource in the parent, and
            // return the modified parent.
            val parent = storeGet(path(path.length - 2))
            parent.deleteSubresource(path, path.length - 1, item.id)
        } else {

            // TODO : If the subresource is a rule, delete the rule from the
            // TODO : chain. This will not be needed when we shall support
            // TODO : bindings between chains and rules.

            if (path.last.attr.zoom.clazz() == classOf[Topology.Rule]) {
                val rule = storeGet(classOf[Topology.Rule], path.last.id.protoId)
                val chain = storeGet(classOf[Topology.Chain], rule.getChainId)
                val ruleIds = chain.getRuleIdsList.asScala - rule.getId
                chain.toBuilder.clearRuleIds().addAllRuleIds(ruleIds.asJava)
                log.info("Chain update for rule delete: {}/{}\n{}",
                         chain.getClass, chain.getProtoId, chain)
                tryWithRaceProtection {
                    backend.store.update(chain)
                }
            }

            // TODO : End

            // The resource is neither referenced, nor embedded: only delete the
            // resource.
            storeDelete(path.last)

            null
        }
    }

    @inline
    @throws[NotFoundException]
    private def storeGet(item: RequestItem): Message = {
        Await.result(backend.store.get(item.attr.zoom.clazz, item.id.protoId),
                     timeout)
    }

    @inline
    @throws[NotFoundException]
    private def storeGet[T <: Message](clazz: Class[T], id: Any): T = {
        Await.result(backend.store.get(clazz, id), timeout)
    }

    @inline
    private def storeGetAll(item: RequestItem): Seq[Message] = {
        Await.result(backend.store.getAll(item.attr.zoom.clazz), timeout)
    }

    @inline
    @throws[NotFoundException]
    private def storeGetAll(item: RequestItem, ids: Set[ResId])
    : Seq[Message] = {
        Await.result(backend.store.getAll(item.attr.zoom.clazz,
                                          ids.map(_.protoId).toSeq),
                     timeout)
    }

    @inline
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    private def storeCreate(item: RequestItem, obj: Message): Unit = {
        if (item.attr.isStorable) {
            log.info("Store create: {}/{}\n{}", obj.getClass, obj.getProtoId,
                     obj)
            tryWithRaceProtection { backend.store.create(obj) }
        }
    }

    @inline
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    private def storeUpdate(item: RequestItem, obj: Message): Unit = {
        if ((obj ne null) && item.attr.isStorable) {
            log.info("Store update: {}/{}\n{}", obj.getClass, obj.getProtoId,
                     obj)
            tryWithRaceProtection { backend.store.update(obj) }
        }
    }

    @inline
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    private def storeDelete(item: RequestItem): Unit = {
        if (item.attr.isStorable) {
            tryWithRaceProtection {
                log.info("Store delete: {}/{}", item.attr.zoom.clazz,
                         item.id.protoId)
                backend.store.delete(item.attr.zoom.clazz, item.id.protoId)
            }
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
                Response.status(HttpStatus.NOT_FOUND_404).build()
            case e: ObjectReferencedException =>
                Response.status(HttpStatus.NOT_ACCEPTABLE_406).build()
            case e: ReferenceConflictException =>
                Response.status(HttpStatus.CONFLICT_409).build()
            case e: ObjectExistsException =>
                Response.status(HttpStatus.CONFLICT_409).build()
        }
    }

    private def tryWithRaceProtection(f: => Unit): Unit = {
        var attempt = storageAttempts
        while (true) {
            try {
                f
                return
            } catch {
                case e: ConcurrentModificationException =>
                    if (attempt > 0) attempt -= 1
                    else throw e
            }
        }
    }
}
