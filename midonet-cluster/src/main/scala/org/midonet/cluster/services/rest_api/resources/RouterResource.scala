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

import org.midonet.cluster.data.ZoomConvert._
import org.midonet.cluster.data.storage.{CreateOp, CreateNodeOp}
import org.midonet.cluster.models.Topology
import org.midonet.midolman.state.PathBuilder

import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.Router
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1)
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

    @POST
    @Consumes(Array(APPLICATION_ROUTER_JSON_V3,
                    APPLICATION_JSON))
    override def create(router: Router,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {

        throwIfViolationsOn(router)

        router.create()

        val pathOps = Seq (
            pathBuilder.getRouterArpTablePath(router.id),
            pathBuilder.getRouterRoutingTablePath(router.id)) map { path =>
                CreateNodeOp(path, null)
            }
        val bridgeOp = CreateOp(toProto(router, classOf[Topology.Router]))
        resContext.backend.store.multi(pathOps :+ bridgeOp)
        router.setBaseUri(resContext.uriInfo.getBaseUri)

        OkCreated(router.getUri)
    }

    protected override def listFilter(routers: Seq[Router]): Future[Seq[Router]] = {
        val tenantId = resContext.uriInfo.getQueryParameters
                                         .getFirst("tenant_id")
        Future.successful(if (tenantId eq null) routers
                          else routers filter { _.tenantId == tenantId })
    }

    protected override def createFilter(router: Router): Ops = {
        router.create()
        NoOps
    }

    protected override def updateFilter(to: Router, from: Router): Ops = {
        to.update(from)
        NoOps
    }
}
