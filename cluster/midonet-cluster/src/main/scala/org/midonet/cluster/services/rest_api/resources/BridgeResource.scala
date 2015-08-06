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
import java.util.UUID
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{MediaType, Response}

import scala.collection.JavaConversions._
import scala.util.Try
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.ResourceUris.{macPortUriToMac, macPortUriToPort}
import org.midonet.cluster.rest_api.VendorMediaType._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Ip4MacPair, Bridge, MacPort}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.{APPLICATION_BRIDGE_COLLECTION_JSON, APPLICATION_BRIDGE_COLLECTION_JSON_V2, APPLICATION_BRIDGE_COLLECTION_JSON_V3, APPLICATION_BRIDGE_JSON, APPLICATION_BRIDGE_JSON_V2, APPLICATION_BRIDGE_JSON_V3}
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext, tryLegacyRead, tryLegacyWrite}
import org.midonet.midolman.state.MacPortMap.encodePersistentPath
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.packets.MAC.InvalidMacException

@RequestScoped
@AllowGet(Array(APPLICATION_BRIDGE_JSON,
                APPLICATION_BRIDGE_JSON_V2,
                APPLICATION_BRIDGE_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_BRIDGE_COLLECTION_JSON,
                 APPLICATION_BRIDGE_COLLECTION_JSON_V2,
                 APPLICATION_BRIDGE_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_BRIDGE_JSON,
                   APPLICATION_BRIDGE_JSON_V2,
                   APPLICATION_BRIDGE_JSON_V3,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_BRIDGE_JSON,
                   APPLICATION_BRIDGE_JSON_V2,
                   APPLICATION_BRIDGE_JSON_V3,
                   APPLICATION_JSON))
@AllowDelete
class BridgeResource @Inject()(resContext: ResourceContext,
                               pathBuilder: PathBuilder,
                               curator: CuratorFramework)
    extends MidonetResource[Bridge](resContext) {

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): BridgePortResource = {
        new BridgePortResource(id, resContext)
    }

    @Path("{id}/dhcp")
    def dhcps(@PathParam("id") id: UUID): DhcpSubnetResource = {
        new DhcpSubnetResource(id, resContext)
    }

    @Path("{id}/dhcpV6")
    def dhcpsv6(@PathParam("id") id: UUID): DhcpV6SubnetResource = {
        new DhcpV6SubnetResource(id, resContext)
    }

    @GET
    @Path("{id}/arp_table/{pair}")
    @Produces(Array(APPLICATION_IP4_MAC_JSON,
                    APPLICATION_JSON))
    def getArpEntry(@PathParam("id") bridgeId: UUID,
                    @PathParam("pair") pair: String): Ip4MacPair = {
        val parts = pair.split("_")
        val address = Try(IPv4Addr.fromString(parts(0))).getOrElse(
            throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID)))
        val mac = Try(MAC.fromString(parts(1).replace('-', ':'))).getOrElse(
            throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID)))

        tryLegacyRead {
            if (resContext.stateTables.bridgeArpTable(bridgeId)
                .contains(address, mac)) {
                new Ip4MacPair(resContext.uriInfo.getBaseUri, bridgeId,
                               address.toString, mac.toString)
            } else {
                throw new NotFoundHttpException(getMessage(ARP_ENTRY_NOT_FOUND))
            }
        }
    }

    @GET
    @Path("{id}/arp_table")
    @Produces(Array(APPLICATION_IP4_MAC_COLLECTION_JSON,
                    APPLICATION_JSON))
    def listArpEntries(@PathParam("id") bridgeId: UUID): util.List[Ip4MacPair] = {
        val entries = tryLegacyRead {
            resContext.stateTables.bridgeArpTable(bridgeId).snapshot
        }
        for ((ip, mac) <- entries.toList)
            yield new Ip4MacPair(resContext.uriInfo.getBaseUri, bridgeId,
                                 ip.toString, mac.toString)
    }

    @POST
    @Path("{id}/arp_table")
    @Consumes(Array(APPLICATION_IP4_MAC_JSON))
    def addArpEntry(@PathParam("id") bridgeId: UUID, arpEntry: Ip4MacPair)
    : Response = {

        throwIfViolationsOn(arpEntry)

        arpEntry.bridgeId = bridgeId
        arpEntry.setBaseUri(resContext.uriInfo.getBaseUri)

        val address = Try(IPv4Addr.fromString(arpEntry.ip)).getOrElse(
            throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID)))
        val mac = Try(MAC.fromString(arpEntry.mac)).getOrElse(
            throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID)))

        tryLegacyWrite {
            resContext.stateTables.bridgeArpTable(bridgeId)
                      .addPersistent(address, mac)
            Response.created(arpEntry.getUri).build()
        }
    }

    @DELETE
    @Path("{id}/arp_table/{pair}")
    def deleteArpEntry(@PathParam("id") bridgeId: UUID,
                       @PathParam("pair") pair: String): Response = {
        val parts = pair.split("_")
        val address = Try(IPv4Addr.fromString(parts(0))).getOrElse(
            throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID)))
        val mac = Try(MAC.fromString(parts(1).replace('-', ':'))).getOrElse(
            throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID)))

        tryLegacyWrite {
            resContext.stateTables.bridgeArpTable(bridgeId).remove(address, mac)
            Response.noContent().build()
        }
    }

    @POST
    @Path("{id}/mac_table")
    @Consumes(Array(APPLICATION_MAC_PORT_JSON,
                    APPLICATION_MAC_PORT_JSON_V2,
                    APPLICATION_JSON))
    def putMacTable(@PathParam("id") bridgeId: UUID, macPort: MacPort)
    : Response = {
        macPort.bridgeId = bridgeId
        macPort.vlanId = if (macPort.vlanId == null) UNTAGGED_VLAN_ID
                         else macPort.vlanId
        putMacTableEntry(macPort)
    }

    @POST
    @Path("{id}/vlans/{vlan_id}/mac_table")
    @Consumes(Array(APPLICATION_MAC_PORT_JSON_V2,
                    MediaType.APPLICATION_JSON))
    def putMacTable(@PathParam("id") id: UUID,
                    @PathParam("vlan_id") vlan: Short,
                    macPort: MacPort): Response = {
        macPort.bridgeId = id
        macPort.vlanId = vlan
        putMacTableEntry(macPort)
    }

    @GET
    @Path("{id}/vlans/{vlan}/mac_table/{mac_port_uri}")
    @Produces(Array(APPLICATION_MAC_PORT_JSON,
                    APPLICATION_MAC_PORT_JSON_V2))
    def getMacPort(@PathParam("id") id: UUID,
                   @PathParam("vlan") vlan: Short,
                   @PathParam("mac_port_uri")s: String): MacPort = {
        macPort(id, s, Some(vlan))
    }

    @GET
    @Path("{id}/mac_table/{mac_port_uri}")
    @Produces(Array(APPLICATION_MAC_PORT_JSON,
                    APPLICATION_MAC_PORT_JSON_V2))
    def getMacPort(@PathParam("id") id: UUID,
                   @PathParam("mac_port_uri")s: String): MacPort = {
        macPort(id, s)
    }

    @DELETE
    @Path("{id}/mac_table/{mac_port_uri}")
    def deleteMacPort(@PathParam("id") id: UUID,
                      @PathParam("mac_port_uri") s: String): Unit = {
        val split = s.split("_")
        val mac = MAC.fromString(split(0).replaceAll("-", ":"))
        doDeleteMacPort(id, mac, UNTAGGED_VLAN_ID,
                        UUID.fromString(split(1)))
    }

    @DELETE
    @Path("{id}/vlans/{vlan}/mac_table/{mac_port_uri}")
    def deleteMacPort(@PathParam("id") id: UUID,
                      @PathParam("vlan") vlan: Short,
                      @PathParam("mac_port_uri") s: String): Unit = {
        val split = s.split("_")
        val mac = MAC.fromString(split(0).replaceAll("-", ":"))
        doDeleteMacPort(id, mac, vlan, UUID.fromString(split(1)))
    }

    @GET
    @Path("{id}/vlans/{vlan}/mac_table")
    @Produces(Array(APPLICATION_MAC_PORT_COLLECTION_JSON_V2))
    def listMacTable(@PathParam("id") id: UUID,
                     @PathParam("vlan") vlan: Short,
                     @HeaderParam("Accept") mediaType: String)
    : util.List[MacPort] = {
        resContext.backend.store.get(classOf[Topology.Network], id).getOrThrow
        macPortsInVlan(id, vlan)
    }

    @GET
    @Path("{id}/mac_table")
    @Produces(Array(APPLICATION_MAC_PORT_COLLECTION_JSON,
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2))
    def listMacTable(@PathParam("id") id: UUID,
                     @HeaderParam("Accept") mediaType: String)
    : util.List[MacPort] = {
        val isV1 = mediaType.equals(APPLICATION_MAC_PORT_COLLECTION_JSON)
        resContext.backend.store.get(classOf[Topology.Network], id).getOrThrow

        if (isV1) {
            macPortsNoVlan(id, isV1 = true)
        } else {
            // Fetch entries in all vlans, if any
            val entriesWithVlan = Try {
                val vlansPath = pathBuilder.getBridgeVlansPath(id)
                curator.getChildren.forPath(vlansPath).flatMap { sVlan =>
                    macPortsInVlan(id, sVlan.toShort)
                }
            }.getOrElse(List.empty)

            // Merge with those in no vlan
            macPortsNoVlan(id, isV1 = false) ++ entriesWithVlan
        }
    }

    // All methods below can easily be extracted to a separate class behind an
    // interface, should the need. Not doing it now as this is the only usage
    // in the v2 stack.

    private def macPortsInVlan(bridgeId: UUID,
                               vlan: java.lang.Short): List[MacPort] = {
        if (vlan == UNTAGGED_VLAN_ID || vlan == null) {
            macPortsNoVlan(bridgeId, isV1 = false)
        } else {
            try {
                val path = pathBuilder.getBridgeMacPortsPath(bridgeId, vlan)
                val children = curator.getChildren.forPath(path)
                toMacPortEntries(children, bridgeId, vlan, isV1 = false)
            } catch {
                case e: NoNodeException =>
                    throw new NotFoundHttpException(
                        s"Bridge $bridgeId has no ports with vlan $vlan")
            }
        }
    }

    private def macPortsNoVlan(bridgeId: UUID, isV1: Boolean): List[MacPort] = {
        val path = pathBuilder.getBridgeMacPortsPath(bridgeId)
        val paths = curator.getChildren.forPath(path)
        toMacPortEntries(paths, bridgeId, null, isV1)
    }

    private def toMacPortEntries(nodes: util.List[String], bridgeId: UUID,
                                 vlan: java.lang.Short, isV1: Boolean)
    : List[MacPort] = nodes.toList.map { n =>
        val pieces = n.split(",")
        val mac = pieces(0)
        val port = UUID.fromString(pieces(1))
        val mp = new MacPort(resContext.uriInfo.getBaseUri, bridgeId, mac, port)
        mp.vlanId = if (isV1 || vlan == null) UNTAGGED_VLAN_ID
        else vlan
        mp
    }

    private def putMacTableEntry(macPort: MacPort): Response = {
        throwIfViolationsOn(macPort)
        val store = resContext.backend.store
        val bridge = store.get(classOf[Topology.Network], macPort.bridgeId)
                           .getOrThrow

        try {
            val p = store.get(classOf[Topology.Port], macPort.portId).getOrThrow
            if (!bridge.getId.equals(p.getNetworkId)) {
                throw new BadRequestHttpException(
                    getMessage(MAC_PORT_ON_BRIDGE))
            }
            if (p.hasVlanId &&
                p.getVlanId != UNTAGGED_VLAN_ID &&
                p.getVlanId != macPort.vlanId) {
                throw new BadRequestHttpException(
                    getMessage(VLAN_ID_MATCHES_PORT_VLAN_ID))
            }
        } catch {
            case _: NotFoundHttpException =>
                throw new BadRequestHttpException(
                    s"Port ${macPort.portId} doesn't exist")
        }

        macPort.setBaseUri(resContext.uriInfo.getBaseUri)
        val mac = MAC.fromString(macPort.macAddr)
        val path = pathBuilder.getBridgeMacPortEntryPath(macPort.bridgeId,
                     macPort.vlanId, encodePersistentPath(mac, macPort.portId))
        try {
            curator.create().creatingParentsIfNeeded().forPath(path)
        } catch {
            case e: NodeExistsException => // ok
            case NonFatal(e) =>
                log.error("Failed to add mac-port entry: " + path, e)

        }
        Response.created(macPort.getUri).build()
    }


    private def doDeleteMacPort(bridgeId: UUID, mac: MAC,
                                vlan: Short, portId: UUID): Response = {
        val store = resContext.backend.store
        store.get(classOf[Topology.Port], portId).getOrThrow
        store.get(classOf[Topology.Network], bridgeId).getOrThrow

        val vlanPath = if (vlan == UNTAGGED_VLAN_ID)
                            pathBuilder.getBridgeMacPortsPath(bridgeId)
                       else pathBuilder.getBridgeMacPortsPath(bridgeId, vlan)
        val path = pathBuilder.getBridgeMacPortEntryPath(bridgeId, vlan,
                                             encodePersistentPath(mac, portId))
        try {
            curator.getData.forPath(vlanPath)
        } catch {
            case _: NoNodeException =>
                throw new NotFoundHttpException(
                    s"Bridge does not have vlan $vlan")
        }

        try {
            curator.delete().deletingChildrenIfNeeded().forPath(path)
        } catch {
            case _: NoNodeException => // ok
        }
        MidonetResource.OkNoContentResponse
    }

    private def macPort(bridgeId: UUID, s: String, vlan: Option[Short] = None)
    : MacPort = {
        val mac = try {
            macPortUriToMac(s)
        } catch {
            case t: InvalidMacException =>
                throw new BadRequestHttpException(getMessage(MAC_URI_FORMAT))
        }
        val portId = macPortUriToPort(s)
        val vlanId = vlan.getOrElse(UNTAGGED_VLAN_ID)

        val store = resContext.backend.store
        store.get(classOf[Topology.Network], bridgeId).getOrThrow
        val path = pathBuilder.getBridgeMacPortEntryPath(
            bridgeId, vlanId, encodePersistentPath(mac, portId))
        val node = try {
            curator.getData.forPath(path)
        } catch {
            case _: NoNodeException => null
        }
        if (node == null) {
            throw new NotFoundHttpException("Entry not found")
        }

        val macPort = new MacPort(resContext.uriInfo.getBaseUri, bridgeId,
                                  mac.toString, portId)
        macPort.vlanId = vlanId
        macPort
    }

    protected def macOrThrow(s: String): MAC = try {
        MAC.fromString(s)
    } catch {
        case t: InvalidMacException =>
            throw new BadRequestHttpException(getMessage(MAC_URI_FORMAT))
    }

    protected override def listFilter(bridges: Seq[Bridge]): Seq[Bridge] = {
        val tenantId = resContext.uriInfo
                                 .getQueryParameters.getFirst("tenant_id")
        if (tenantId eq null) bridges
        else bridges filter { _.tenantId == tenantId }
    }

    protected override def createFilter(to: Bridge): Ops = {
        if (to.vxLanPortId != null || to.vxLanPortIds != null) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        to.create()
        NoOps
    }

    protected override def updateFilter(to: Bridge, from: Bridge): Ops = {
        if (to.vxLanPortId != null &&
            to.vxLanPortId != from.vxLanPortId) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        if (to.vxLanPortIds != null &&
            to.vxLanPortIds != from.vxLanPortIds) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        to.update(from)
        NoOps
    }

}
