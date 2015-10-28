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
import scala.concurrent.Future

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

/**
  * All operations that need to be performed atomically are done by
  * acquiring a ZooKeeper lock. This is to prevent races with
  * midolman, more specifically the health monitor, which operates on:
  * - Route, with side effects on Router and RouterPort due to ZOOM bindings.
  * - Port, with side effects on Router and Host due to ZOOM bindings.
  * - Pool
  * - PoolMember
  *
  * Any api or midolman sections that may be doing something like:
  *
  *   val port = store.get(classOf[Port], id)
  *   val portModified = port.toBuilder() -alter fields- .build()
  *   store.update(portModified)
  *
  * Might overwrite changes made by this class on the port (directly, or
  * implicitly as a result of referential constraints).  To protect
  * against this, sections of the code similar to the one above should
  * be protected using the following lock:
  *
  *   new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
  *                ZookeeperLockFactory.ZOOM_TOPOLOGY)
  */
@ApiResource(version = 1)
@Path("hosts")
@RequestScoped
@AllowGet(Array(APPLICATION_HOST_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_HOST_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
class HostResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Host](resContext) {

    protected final override val zkLockNeeded = true

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        if (isAlive(id)) {
            return buildErrorResponse(
                Status.FORBIDDEN,
                getMessage(HOST_DELETION_NOT_ALLOWED_ACTIVE, id))
        }
        val host = getResource(classOf[Host], id).getOrThrow
        if ((host.portIds ne null) && !host.portIds.isEmpty) {
            return buildErrorResponse(
                Status.FORBIDDEN,
                getMessage(HOST_DELETION_NOT_ALLOWED_BINDINGS,
                           id, Integer.valueOf(host.portIds.size())))
        }
        var ops = for (tunnelZoneId <- host.tunnelZoneIds.asScala) yield {
            val tunnelZone =
                getResource(classOf[TunnelZone], tunnelZoneId).getOrThrow
            tunnelZone.removeHost(host.id)
            Update(tunnelZone).asInstanceOf[Multi]
        }
        ops = ops :+ Delete(classOf[Host], id)
        multiResource(ops)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_HOST_JSON_V3,
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
                buildErrorResponse(
                    Status.BAD_REQUEST,
                    getMessage(HOST_FLOODING_PROXY_WEIGHT_IS_NULL))
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

    protected override def getFilter(host: Host): Future[Host] = {
        Future.successful(initHost(host))
    }

    protected override def listFilter(hosts: Seq[Host]): Future[Seq[Host]] = {
        hosts foreach initHost
        Future.successful(hosts)
    }

    private def initHost(host: Host): Host = {
        val interfaces = getInterfaces(host.id.toString).getOrThrow
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
            .getOrThrow.nonEmpty
    }

    private def getInterfaces(hostId: String): Future[Seq[Interface]] = {
        getResourceState(hostId.toString,classOf[Host], hostId,
                         MidonetBackend.HostKey).map {
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
