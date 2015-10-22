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

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.ZoomConvert.fromProto
import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Route.NextHop
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, InternalServerErrorHttpException, NotFoundHttpException, ConflictHttpException}
import org.midonet.cluster.services.MidonetBackend.{ActiveKey, BgpKey}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.SequenceDispenser.OverlayTunnelKey
import org.midonet.cluster.util.UUIDUtil._

class AbstractPortResource[P >: Null <: Port] (resContext: ResourceContext)
                                              (implicit tag: ClassTag[P])
    extends MidonetResource[P](resContext)(tag) {

    protected override def getFilter(port: P): Future[P] = {
        setActive(port)
        setBgpStatus(port)
        Future.successful(port)
    }

    protected override def listFilter(ports: Seq[P]): Future[Seq[P]] = {
        ports foreach { port => setActive(port); setBgpStatus(port) }
        Future.successful(ports)
    }

    private def isActive(port: Port): Boolean = {
        getResourceState(port.hostId.asNullableString, classOf[Port],
                         port.id.toString, ActiveKey).getOrThrow.nonEmpty
    }

    private def setActive(port: P): Unit = {
        port.active = isActive(port)
    }

    private def setBgpStatus(port: P): Unit = {
        port match {
            case routerPort: RouterPort =>
                getResourceState(port.hostId.asNullableString, classOf[Port],
                                 port.id.toString, BgpKey).getOrThrow match {
                    case SingleValueKey(_, Some(status), _) =>
                        routerPort.bgpStatus = status
                    case _ =>
                        routerPort.bgpStatus = null
                }
            case _ =>
        }
    }

    protected def ensureTunnelKey(port: P): Future[P] = {
        resContext.seqDispenser.next(OverlayTunnelKey) map { key =>
            port.tunnelKey = key
            port
        } recover { case NonFatal(t) =>
            log.error("Failed to generate tunnel key", t)
            throw new InternalServerErrorHttpException(
                s"Unable to generate new tunnel key: ${t.getMessage}")
        }
    }

}

@ApiResource(version = 1)
@Path("ports")
@RequestScoped
@AllowGet(Array(APPLICATION_PORT_V2_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortResource @Inject()(resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = {
        val Seq(port, peer) =
            listResources(classOf[Port], Seq(id, link.peerId)).getOrThrow
        if ((port.peerId eq null) && (peer.peerId eq null)) {
            port.peerId = link.peerId
            updateResource(port, Response.created(link.getUri).build())
        } else {
            buildErrorResponse(BAD_REQUEST.getStatusCode,
                               getMessage(PORTS_LINKABLE))
        }
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

    protected override def deleteFilter(id: String): Ops = {
        val port = backend.store.get(classOf[Topology.Port], id).getOrThrow
        if (port.hasVtepId) {
            val vtepId = port.getVtepId.asJava
            val nwId = port.getNetworkId.asJava
            throw new ConflictHttpException("This port type doesn't allow  " +
                s"deletions as it binds network $nwId to VTEP $vtepId. " +
                "Please remove the bindings instead.")
        }
        NoOps
    }

    protected override def updateFilter(to: Port, from: Port): Ops = {
        to.update(from)
        NoOps
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class BridgePortResource @Inject()(bridgeId: UUID,
                                   resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    protected override def listIds: Ids = {
        getResource(classOf[Bridge], bridgeId) map { _.portIds.asScala }
    }

    protected override def createFilter(port: Port): Ops = {
        val bridgePort = port match {
            case bp: BridgePort => bp
            case _ =>
                throw new BadRequestHttpException("Not a bridge port.")
        }
        ensureTunnelKey(port) flatMap { _ =>
            bridgePort.create(bridgeId)
            NoOps
        }
    }

    @GET
    @Path("{id}/vtep_bindings/{physPort}/{vlan}")
    def vtepBindings(@PathParam("id")id: UUID,
                     @PathParam("physPort")physPort: String,
                     @PathParam("vlan")vlan: Int): List[VtepBinding] = {
        getBindings(id).filter { b =>
            b.vlanId == vlan && physPort == b.portName
        }
    }

    @GET
    @Path("{id}/vtep_bindings")
    def vtepBindings(@PathParam("id")id: UUID): List[VtepBinding] = {
        getBindings(id)
    }

    private def getBindings(id: UUID): List[VtepBinding] = {
        val p = backend.store.get(classOf[Topology.Port], id).getOrThrow
        if (!p.hasVtepId) {
            throw new BadRequestHttpException(getMessage(PORT_NOT_VXLAN_PORT,
                                                         id))
        }
        val vtep = backend.store.get(classOf[Topology.Vtep], p.getVtepId)
                          .getOrThrow
        val bindings = vtep.getBindingsList
                           .filter { _.getNetworkId == p.getNetworkId }
                           .map { fromProto(_, classOf[VtepBinding]) }
                           .toList
        if (bindings.isEmpty) {
            log.warn("Network VxLAN port $id exists, but no bindings are " +
                     "found in VTEP ${fromProto(vtep.getId()}, this is wrong" +
                     "as the port is deleted when the last binding is removed" +
                     ". Data inconsistency?")
        }
        bindings
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_V2_JSON,
                   APPLICATION_JSON))
class RouterPortResource @Inject()(routerId: UUID, resContext: ResourceContext)
    extends AbstractPortResource[RouterPort](resContext) {

    protected override def listIds: Ids = {
        getResource(classOf[Router], routerId) map { _.portIds.asScala }
    }

    protected override def createFilter(port:RouterPort): Ops = {
        ensureTunnelKey(port) map { _ =>
            port.create(routerId)
            port.setBaseUri(resContext.uriInfo.getBaseUri)
            val route = new Route("0.0.0.0", 0, port.portAddress, 32,
                                  NextHop.Local, port.id, "255.255.255.255", 0,
                                  null, false)
            route.setBaseUri(resContext.uriInfo.getBaseUri)
            Seq(Create(route))
        }
    }

}

@RequestScoped
class BridgePeerPortResource @Inject()(bridgeId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val bridge = getResource(classOf[Bridge], bridgeId).getOrThrow
        val ports =
            listResources(classOf[Port], bridge.portIds.asScala).getOrThrow
        val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
        listResources(classOf[Port], peerPortIds).getOrThrow.asJava
    }

}

@RequestScoped
class BridgeVxlanPortResource @Inject()(bridgeId: UUID,
                                        resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val bridge = getResource(classOf[Bridge], bridgeId).getOrThrow
        listResources(classOf[Port], bridge.vxLanPortIds.asScala).getOrThrow.asJava
    }

}

@RequestScoped
class RouterPeerPortResource @Inject()(routerId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V2_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val router = getResource(classOf[Router], routerId).getOrThrow
        val ports =
            listResources(classOf[Port], router.portIds.asScala).getOrThrow
        val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
        listResources(classOf[Port], peerPortIds).getOrThrow.asJava
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
