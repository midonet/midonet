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

import java.util.{UUID, List => JList}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status._

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.ZoomConvert.fromProto
import org.midonet.cluster.data.storage.SingleValueKey
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Route.NextHop
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.MidonetBackend.{ActiveKey, BgpKey}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.SequenceDispenser.OverlayTunnelKey
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Addr, MAC}

class AbstractPortResource[P >: Null <: Port] (resContext: ResourceContext)
                                              (implicit tag: ClassTag[P])
    extends MidonetResource[P](resContext)(tag) {

    protected override def getFilter(port: P): P = {
        setActive(port)
        setBgpStatus(port)
        port
    }

    protected override def listFilter(ports: Seq[P]): Seq[P] = {
        ports foreach { port => setActive(port); setBgpStatus(port) }
        ports
    }

    private def isActive(port: Port): Boolean = {
        getResourceState(port.hostId.asNullableString, classOf[Port],
                         port.id.toString, ActiveKey).nonEmpty
    }

    private def setActive(port: P): Unit = {
        port.active = isActive(port)
    }

    private def setBgpStatus(port: P): Unit = {
        port match {
            case routerPort: RouterPort =>
                getResourceState(port.hostId.asNullableString, classOf[Port],
                                 port.id.toString, BgpKey) match {
                    case SingleValueKey(_, Some(status), _) =>
                        routerPort.bgpStatus = status
                    case _ =>
                        routerPort.bgpStatus = null
                }
            case _ =>
        }
    }

    protected def ensureTunnelKey(port: P): P = {
        resContext.seqDispenser.next(OverlayTunnelKey) map { key =>
            port.tunnelKey = key
            port
        } recover { case NonFatal(t) =>
            log.error("Failed to generate tunnel key", t)
            throw new InternalServerErrorHttpException(
                s"Unable to generate new tunnel key: ${t.getMessage}")
        } getOrThrow
    }

}

@ApiResource(version = 1, name = "ports", template = "portTemplate")
@Path("ports")
@RequestScoped
@AllowGet(Array(APPLICATION_PORT_V3_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_PORT_V3_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortResource @Inject()(resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @POST
    @Path("{id}/link")
    @Consumes(Array(APPLICATION_PORT_LINK_JSON,
                    APPLICATION_JSON))
    def link(@PathParam("id") id: UUID, link: Link): Response = tryTx { tx =>
        val Seq(port, peer) = tx.list(classOf[Port], Seq(id, link.peerId))
        if ((port.peerId eq null) && (peer.peerId eq null)) {
            port.peerId = link.peerId
            tx.update(port)
            Response.created(link.getUri).build()
        } else {
            buildErrorResponse(CONFLICT.getStatusCode,
                               getMessage(PORTS_LINKABLE))
        }
    }

    @DELETE
    @Path("{id}/link")
    def unlink(@PathParam("id") id: UUID): Response = tryTx { tx =>
        val port = tx.get(classOf[Port], id)
        port.peerId = null
        tx.update(port)
        OkNoContentResponse
    }

    @Path("{id}/port_groups")
    def portGroups(@PathParam("id") id: UUID): PortPortGroupResource = {
        new PortPortGroupResource(id, resContext)
    }

    protected override def deleteFilter(id: String,
                                        tx: ResourceTransaction): Unit = {
        val port = tx.get(classOf[VxLanPort], id)
        if (port.vtepId ne null) {
            throw new ConflictHttpException("This port type doesn't allow  " +
                s"deletions as it binds network ${port.networkId} to VTEP " +
                s"${port.vtepId}. Please remove the bindings instead.")
        }
        tx.delete(classOf[Port], id)
    }

    protected override def updateFilter(to: Port, from: Port,
                                        tx: ResourceTransaction): Unit = {
        to.update(from)
        tx.update(to)
    }

}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_V3_JSON,
                   APPLICATION_JSON))
class BridgePortResource @Inject()(bridgeId: UUID,
                                   resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Bridge], bridgeId).portIds.asScala
    }

    protected override def createFilter(port: Port,
                                        tx: ResourceTransaction): Unit = {
        val bridgePort = port match {
            case bp: BridgePort => bp
            case _ =>
                throw new BadRequestHttpException("Not a bridge port.")
        }
        ensureTunnelKey(port)
        bridgePort.create(bridgeId)
        tx.create(bridgePort)
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
        val p = store.get(classOf[Topology.Port], id).getOrThrow
        if (!p.hasVtepId) {
            throw new BadRequestHttpException(getMessage(PORT_NOT_VXLAN_PORT,
                                                         id))
        }
        val vtep = store.get(classOf[Topology.Vtep], p.getVtepId).getOrThrow
        val bindings =
            vtep.getBindingsList.asScala
                                .filter { _.getNetworkId == p.getNetworkId }
                                .map { fromProto(_, classOf[VtepBinding]) }
                                .toList
        if (bindings.isEmpty) {
            log.warn("Network VXLAN port {} exists, but no bindings are " +
                     "found in VTEP {}, this is wrong as the port is deleted " +
                     "when the last binding is removed. Data inconsistency?",
                     id, vtep.getId.asJava)
        }
        bindings
    }
}

@RequestScoped
@AllowList(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_V3_JSON,
                   APPLICATION_JSON))
class RouterPortResource @Inject()(routerId: UUID, resContext: ResourceContext)
    extends AbstractPortResource[RouterPort](resContext) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[Router], routerId).portIds.asScala
    }

    protected override def createFilter(port:RouterPort,
                                        tx: ResourceTransaction): Unit = {
        ensureTunnelKey(port)
        port.create(routerId)
        port.setBaseUri(resContext.uriInfo.getBaseUri)
        tx.create(port)

        val route = new Route("0.0.0.0", 0, port.portAddress, 32,
                              NextHop.Local, port.id, null, 0,
                              null, false)
        route.setBaseUri(resContext.uriInfo.getBaseUri)
        tx.create(route)
    }

    protected override def updateFilter(to: RouterPort, from: RouterPort,
                                        tx: ResourceTransaction): Unit = {
        super.updateFilter(to, from, tx)
    }

    private def peeringTable(id: UUID) =
        resContext.backend.stateTableStore.portPeeringTable(id)

    @GET
    @Path("{id}/peering_table/{pair}")
    @Produces(Array(APPLICATION_MAC_IP_JSON,
        APPLICATION_JSON))
    def getPeeringEntry(@PathParam("id") portId: UUID,
                        @PathParam("pair") pair: String): MacIpPair = {
        val (address, mac) = parseIpMac(pair)

        def trimmedPath = resContext.uriInfo.getRequestUri.getPath.replaceFirst("\\/" + pair, "")

        tryLegacyRead {
            val table = peeringTable(portId)
            if (Await.result(table.containsRemote(mac, address), requestTimeout)) {
                new MacIpPair(resContext.uriInfo.getRequestUriBuilder.replacePath(trimmedPath).build(),
                              address.toString, mac.toString)
            } else {
                throw new NotFoundHttpException(getMessage(PEERING_ENTRY_NOT_FOUND))
            }
        }
    }

    @GET
    @Path("{id}/peering_table")
    @Produces(Array(APPLICATION_MAC_IP_COLLECTION_JSON,
        APPLICATION_JSON))
    def listPeeringEntries(@PathParam("id") portId: UUID): JList[MacIpPair] = {
        import scala.collection.JavaConversions._

        val entries = tryLegacyRead {
            val table = peeringTable(portId)

            Await.result(table.remoteSnapshot, requestTimeout)
        }

        for ((mac, ip) <- entries.toList)
            yield new MacIpPair(resContext.uriInfo.getRequestUri, ip.toString,
                                mac.toString)
    }

    @POST
    @Path("{id}/peering_table")
    @Consumes(Array(APPLICATION_MAC_IP_JSON))
    def addPeeringEntry(@PathParam("id") portId: UUID, entry: MacIpPair) : Response = {

        throwIfViolationsOn(entry)

        entry.setBaseUri(resContext.uriInfo.getRequestUri)

        val address = Try(IPv4Addr.fromString(entry.ip)).getOrElse(
            throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID)))
        val mac = Try(MAC.fromString(entry.mac)).getOrElse(
            throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID)))

        tryLegacyWrite {
            val table = peeringTable(portId)
            Await.result(table.addPersistent(mac, address), requestTimeout)
            Response.created(entry.getUri).build()
        }
    }

    @DELETE
    @Path("{id}/peering_table/{pair}")
    def deletePeeringEntry(@PathParam("id") portId: UUID,
                           @PathParam("pair") pair: String): Response = {
        val (address, mac) = parseIpMac(pair)

        tryLegacyWrite {
            val table = peeringTable(portId)
            Await.result(table.removePersistent(mac, address), requestTimeout)
            Response.noContent().build()
        }
    }

    private def parseIpMac(pair: String): (IPv4Addr, MAC) = {
        val tokens = pair.split("_")
        val address =
            try IPv4Addr.fromString(tokens(0))
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID))
            }
        val mac =
            try MAC.fromString(tokens(1).replace('-', ':'))
            catch { case NonFatal(_) =>
                throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID))
            }
        (address, mac)
    }

}

@RequestScoped
class BridgePeerPortResource @Inject()(bridgeId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val bridge = getResource(classOf[Bridge], bridgeId)
        val ports =
            listResources(classOf[Port], bridge.portIds.asScala)
        val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
        listResources(classOf[Port], peerPortIds).asJava
    }

}

@RequestScoped
class BridgeVxlanPortResource @Inject()(bridgeId: UUID,
                                        resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val bridge = getResource(classOf[Bridge], bridgeId)
        listResources(classOf[Port], bridge.vxLanPortIds.asScala).asJava
    }

}

@RequestScoped
class RouterPeerPortResource @Inject()(routerId: UUID,
                                       resContext: ResourceContext)
    extends AbstractPortResource[Port](resContext) {

    @GET
    @Produces(Array(APPLICATION_PORT_V3_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Port] = {
        val router = getResource(classOf[Router], routerId)
        val ports =
            listResources(classOf[Port], router.portIds.asScala)
        val peerPortIds = ports.filter(_.peerId ne null).map(_.peerId)
        listResources(classOf[Port], peerPortIds).asJava
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
        val portGroup = getResource(classOf[PortGroup], portGroupId)
        val portGroupPorts = listResources(classOf[PortGroupPort],
                                           portGroup.portIds.asScala)
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
    : Response = tryTx { tx =>
        portGroupPort.portGroupId = portGroupId

        throwIfViolationsOn(portGroupPort)

        val port = tx.get(classOf[Port], portGroupPort.portId)
        val pg = tx.get(classOf[PortGroup], portGroupPort.portGroupId)
        if (port.portGroupIds.contains(portGroupPort.portGroupId)) {
            Response.status(Status.CONFLICT).build()
        } else  if (pg.portIds.contains(portGroupPort.portId)) {
            Response.status(Status.CONFLICT).build()
        } else {
            portGroupPort.setBaseUri(resContext.uriInfo.getBaseUri)
            port.portGroupIds.add(portGroupPort.portGroupId)
            pg.portIds.add(portGroupPort.portId)
            tx.update(port)
            tx.update(pg)
            Response.created(portGroupPort.getUri).build()
        }
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = tryTx { tx =>
        val portId = UUID.fromString(id)
        // If the port still exists, delete the port group ID from the port,
        // otherwise delete only the port ID from the port group.
        try {
            val port = tx.get(classOf[Port], portId)
            val pg = tx.get(classOf[PortGroup], portGroupId)
            if (!port.portGroupIds.contains(portGroupId)) {
                Response.status(Status.NOT_FOUND).build()
            } else if (!pg.portIds.contains(portId)) {
                OkNoContentResponse
            } else {
                port.portGroupIds.remove(portGroupId)
                pg.portIds.remove(portId)
                tx.update(port)
                tx.update(pg)
                OkNoContentResponse
            }
        } catch {
            case NonFatal(_) =>
                val pg = tx.get(classOf[PortGroup], portGroupId)
                if (!pg.portIds.contains(portId)) {
                    OkNoContentResponse
                } else {
                    pg.portIds.remove(portId)
                    tx.update(pg)
                    OkNoContentResponse
                }
        }
    }
}
