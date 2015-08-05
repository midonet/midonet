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
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status._

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Route.NextHop
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{InternalServerErrorHttpException, NotFoundHttpException}
import org.midonet.cluster.services.MidonetBackend.HostsKey
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{Create, ResourceContext, Update}
import org.midonet.cluster.util.SequenceType

class AbstractPortResource[P >: Null <: Port] (resContext: ResourceContext)
                                              (implicit tag: ClassTag[P])
    extends MidonetResource[P](resContext)(tag) {

    protected override def getFilter = (port: P) => setActive(port)

    protected override def listFilter = (port: P) => { setActive(port); true }

    private def isActive(id: String): Boolean = {
        getResourceState(classOf[Port], id, HostsKey).getOrThrow.nonEmpty
    }

    private def setActive(port: P): P = {
        port.active = isActive(port.id.toString)
        port
    }

    protected[resources] def ensureTunnelKey(port: P): Unit = try {
        port.tunnelKey = Await.result (
            resContext.seqDispenser.next(SequenceType.OverlayTunnelKey),
            MidonetResource.Timeout
        ).toInt
    } catch {
        case NonFatal(t) =>
            log.error("Failed to generate tunnel key", t)
            throw new InternalServerErrorHttpException(
                s"Unable to generate new tunnel key: ${t.getMessage}")
    }

}

@RequestScoped
@AllowGet(Array(APPLICATION_PORT_JSON,
                APPLICATION_PORT_V2_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortResource @Inject()(resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = {
        getResource(classOf[Port], id) flatMap { port =>
            getResource(classOf[Port], link.peerId) map { peerPort =>
                if ((port.peerId eq null) && (peerPort.peerId eq null)) {
                    port.peerId = link.peerId
                    updateResource(port, Response.created(link.getUri).build())
                } else {
                    buildErrorResponse(BAD_REQUEST.getStatusCode,
                                       getMessage(PORTS_LINKABLE))
                }
            }
        } getOrThrow
    }

    @DELETE
    @Path("{id}/link")
    def unlink(@PathParam("id") id: UUID): Response = {
        getResource(classOf[Port], id) map { port =>
            port.peerId = null
            updateResource(port, MidonetResource.OkNoContentResponse)
        } getOrThrow
    }

    @Path("{id}/port_groups")
    def portGroups(@PathParam("id") id: UUID): PortPortGroupResource = {
        new PortPortGroupResource(id, resContext)
    }

    // The final is not totally required, but it just helps confirming that
    // nobody is overriding the tunnelKey check below
    protected final override def updateFilter = (to: Port, from: Port) => {
        to.tunnelKey = from.tunnelKey // disallow updating
        to.update(from)
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_JSON,
                   APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class BridgePortResource @Inject()(bridgeId: UUID,
                                   resContext: ResourceContext)
    extends AbstractPortResource[BridgePort](resContext) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == bridgeId
    }

    protected override def createFilter = (port: BridgePort) => {
        ensureTunnelKey(port)
        port.create(bridgeId)
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_COLLECTION_JSON,
                 APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
class RouterPortResource @Inject()(routerId: UUID, resContext: ResourceContext)
    extends AbstractPortResource[RouterPort](resContext) {

    protected override def listFilter = (port: Port) => {
        port.getDeviceId == routerId
    }

    @POST
    @Consumes(Array(APPLICATION_PORT_JSON,
                    APPLICATION_PORT_V2_JSON,
                    APPLICATION_JSON))
    override def create(port: RouterPort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        throwIfViolationsOn(port)

        ensureTunnelKey(port)
        port.create(routerId)
        port.setBaseUri(resContext.uriInfo.getBaseUri)
        val route = new Route("0.0.0.0", 0, port.portAddress, 32, NextHop.Local,
                              port.id, "255.255.255.255", 0, routerId, false)
        route.setBaseUri(resContext.uriInfo.getBaseUri)
        multiResource(Seq(Create(port), Create(route)),
                      Response.created(port.getUri).build())
    }

}

@RequestScoped
class BridgePeerPortResource @Inject()(routerId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        (getResource(classOf[Bridge], routerId) flatMap { router =>
            listResources(classOf[Port], router.portIds.asScala)
        } flatMap { ports =>
            val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
            listResources(classOf[Port], peerPortIds)
         } getOrThrow).asJava
    }

}

@RequestScoped
class RouterPeerPortResource @Inject()(routerId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_COLLECTION_JSON,
                    APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        (getResource(classOf[Router], routerId) flatMap { router =>
            listResources(classOf[Port], router.portIds.asScala)
        } flatMap { ports =>
            val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
            listResources(classOf[Port], peerPortIds)
         } getOrThrow).asJava
    }

}

@RequestScoped
class PortGroupPortResource @Inject()(portGroupId: UUID,
                                      resContext: ResourceContext)
    extends MidonetResource[PortGroupPort](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORTGROUP_PORT_JSON,
                    APPLICATION_JSON))
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): PortGroupPort = {
        val portId = UUID.fromString(id)
        val portGroupPort = getResource(classOf[PortGroupPort], portId)
            .getOrThrow
        if (!portGroupPort.portGroupIds.contains(portGroupId)) {
            throw new NotFoundHttpException("Resource not found")
        }
        portGroupPort.portGroupId = portGroupId
        portGroupPort
    }

    @GET
    @Produces(Array(APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[PortGroupPort] = {
        val portGroupPorts = getResource(classOf[PortGroup], portGroupId)
            .flatMap(pg => listResources(classOf[PortGroupPort],
                                         pg.portIds.asScala))
            .getOrThrow
        portGroupPorts.foreach(portGroupPort => {
            if (!portGroupPort.portGroupIds.contains(portGroupId)) {
                throw new NotFoundHttpException("Resource not found")
            }
            portGroupPort.portGroupId = portGroupId
        })
        portGroupPorts.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_PORTGROUP_PORT_JSON,
                    APPLICATION_JSON))
    override def create(portGroupPort: PortGroupPort,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        portGroupPort.portGroupId = portGroupId

        throwIfViolationsOn(portGroupPort)

        getResource(classOf[Port], portGroupPort.portId).flatMap(port => {
            getResource(classOf[PortGroup], portGroupPort.portGroupId).map(pg => {
                if (port.portGroupIds.contains(portGroupPort.portGroupId)) {
                    Response.status(Status.CONFLICT).build()
                } else  if (pg.portIds.contains(portGroupPort.portId)) {
                    Response.status(Status.CONFLICT).build()
                } else {
                    portGroupPort.setBaseUri(resContext.uriInfo.getBaseUri)
                    port.portGroupIds.add(portGroupPort.portGroupId)
                    pg.portIds.add(portGroupPort.portId)
                    multiResource(Seq(Update(port), Update(pg)),
                                  Response.created(portGroupPort.getUri)
                                      .build())
                }
            })
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        val portId = UUID.fromString(id)
        // If the port still exists, delete the port group ID from the port,
        // otherwise delete only the port ID from the port group.
        getResource(classOf[Port], portId).flatMap(port => {
            getResource(classOf[PortGroup], portGroupId).map(pg => {
                if (!port.portGroupIds.contains(portGroupId)) {
                    Response.status(Status.NOT_FOUND).build()
                } else if (!pg.portIds.contains(portId)) {
                    MidonetResource.OkNoContentResponse
                } else {
                    port.portGroupIds.remove(portGroupId)
                    pg.portIds.remove(portId)
                    multiResource(Seq(Update(port), Update(pg)),
                                  MidonetResource.OkNoContentResponse)
                }
            })
        }).fallbackTo(
            getResource(classOf[PortGroup], portGroupId).map(pg => {
                if (!pg.portIds.contains(portId)) {
                    MidonetResource.OkNoContentResponse
                } else {
                    pg.portIds.remove(portId)
                    updateResource(pg, MidonetResource.OkNoContentResponse)
                }
            }))
        .getOrThrow
    }
}