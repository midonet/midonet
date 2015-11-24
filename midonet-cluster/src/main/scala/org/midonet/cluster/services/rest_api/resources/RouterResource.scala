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
import java.util.{List => JList}

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import scala.util.Try

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.{NotFoundHttpException, BadRequestHttpException}
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{MacIpPair, Router}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.{IPv4Addr, MAC}

@ApiResource(version = 1, name = "routers", template = "routerTemplate")
@Path("routers")
@RequestScoped
@AllowGet(Array(APPLICATION_ROUTER_JSON_V3,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_ROUTER_COLLECTION_JSON_V3,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_ROUTER_JSON_V3,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_ROUTER_JSON_V3,
                   APPLICATION_JSON))
@AllowDelete
class RouterResource @Inject()(resContext: ResourceContext,
                               pathBuilder: PathBuilder)
    extends MidonetResource[Router](resContext) {

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): RouterPortResource = {
        new RouterPortResource(id, resContext)
    }

    @Path("{id}/peer_ports")
    def peerPorts(@PathParam("id") id: UUID): RouterPeerPortResource = {
        new RouterPeerPortResource(id, resContext)
    }

    @Path("{id}/routes")
    def routes(@PathParam("id") id: UUID): RouterRouteResource = {
        new RouterRouteResource(id, resContext)
    }

    @Path("{id}/bgp_networks")
    def bgpNetworks(@PathParam("id") id: UUID): RouterBgpNetworkResource = {
        new RouterBgpNetworkResource(id, resContext)
    }

    @Path("{id}/bgp_peers")
    def bgpPeers(@PathParam("id") id: UUID): RouterBgpPeerResource = {
        new RouterBgpPeerResource(id, resContext)
    }

    private def peeringTable(id: UUID) = resContext.backend.stateTableStore.routerPeeringTable(id)

    @GET
    @Path("{id}/peering_table/{pair}")
    @Produces(Array(APPLICATION_MAC_IP_JSON,
                    APPLICATION_JSON))
    def getPeeringEntry(@PathParam("id") routerId: UUID,
                        @PathParam("pair") pair: String): MacIpPair = {
        val (address, mac) = parseMacIpPair(pair)

        tryLegacyRead {
            val table = peeringTable(routerId)
            if (table.containsRemote(address, mac)) {
                new MacIpPair(resContext.uriInfo.getBaseUri, routerId,
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
    def listPeeringEntries(@PathParam("id") routerId: UUID): JList[MacIpPair] = {
        import scala.collection.JavaConversions._

        val entries = tryLegacyRead {
            val table = peeringTable(routerId)

            table.remoteSnapshot
        }
        for ((ip, mac) <- entries.toList)
            yield new MacIpPair(resContext.uriInfo.getBaseUri, routerId,
                ip.toString, mac.toString)
    }

    @POST
    @Path("{id}/peering_table")
    @Consumes(Array(APPLICATION_MAC_IP_JSON))
    def addPeeringEntry(@PathParam("id") routerId: UUID, entry: MacIpPair) : Response = {

        throwIfViolationsOn(entry)

        entry.deviceId = routerId
        entry.setBaseUri(resContext.uriInfo.getBaseUri)

        val address = Try(IPv4Addr.fromString(entry.ip)).getOrElse(
            throw new BadRequestHttpException(getMessage(IP_ADDR_INVALID)))
        val mac = Try(MAC.fromString(entry.mac)).getOrElse(
            throw new BadRequestHttpException(getMessage(MAC_ADDRESS_INVALID)))

        tryLegacyWrite {
            val table = peeringTable(routerId)
            table.addPersistent(address, mac)
            Response.created(entry.getUri).build()
        }
    }

    @DELETE
    @Path("{id}/peering_table/{pair}")
    def deletePeeringEntry(@PathParam("id") routerId: UUID,
                       @PathParam("pair") pair: String): Response = {
        val (address, mac) = parseMacIpPair(pair)

        tryLegacyWrite {
            val table = peeringTable(routerId)
            table.removePersistent(address, mac)
            Response.noContent().build()
        }
    }

    protected override def listFilter(routers: Seq[Router]): Seq[Router] = {
        val tenantId = resContext.uriInfo.getQueryParameters
                                         .getFirst("tenant_id")
        if (tenantId eq null) routers
        else routers filter { _.tenantId == tenantId }
    }

    protected override def createFilter(router: Router, tx: ResourceTransaction)
    : Unit = {
        tx.create(router)
        tx.tx.createNode(pathBuilder.getRouterArpTablePath(router.id), null)
        tx.tx.createNode(pathBuilder.getRouterRoutingTablePath(router.id), null)
    }

    protected override def updateFilter(to: Router, from: Router,
                                        tx: ResourceTransaction): Unit = {
        to.update(from)
        tx.update(to)
    }
}
