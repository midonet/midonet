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

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.{Bgp, RouterPort}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{Create, Delete, ResourceContext, Update}

@RequestScoped
class BgpResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Bgp](resContext) {

    @GET
    @Produces(Array(APPLICATION_BGP_JSON,
                    APPLICATION_JSON))
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): Bgp = {
        getResource(classOf[Bgp], id).flatMap(bgp => {
            getResource(classOf[RouterPort], bgp.portId).map(port => {
                bgp.localAS = port.localAs
                bgp.adRouteIds = port.bgpNetworkIds
                bgp
            })
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        getResource(classOf[Bgp], id).flatMap(bgp => {
            getResource(classOf[RouterPort], bgp.portId).map(port => {
                port.localAs = RouterPort.NO_AS
                port.bgpNetworkIds.clear()
                multiResource(Seq(Update(port), Delete(classOf[Bgp], id)),
                              MidonetResource.OkNoContentResponse)
            })
        }).getOrThrow
    }

    @Path("{id}/ad_routes")
    def adRoutes(@PathParam("id") id: UUID): BgpAdRouteResource = {
        new BgpAdRouteResource(id, resContext)
    }

}

@RequestScoped
class PortBgpResource @Inject()(portId: UUID, resContext: ResourceContext)
    extends MidonetResource[Bgp](resContext) {

    @GET
    @Produces(Array(APPLICATION_BGP_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Bgp] = {
        getResource(classOf[RouterPort], portId).flatMap(port => {
            listResources(classOf[Bgp], port.bgpPeerIds.asScala).map(list => {
                list.foreach(bgp => {
                    bgp.localAS = port.localAs
                    bgp.adRouteIds = port.bgpNetworkIds
                })
                list
            })
        }).getOrThrow.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_BGP_JSON,
                    APPLICATION_JSON))
    override def create(bgp: Bgp,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        bgp.setBaseUri(resContext.uriInfo.getBaseUri)
        throwIfViolationsOn(bgp)

        getResource(classOf[RouterPort], portId).map(port => {
            if (port.localAs != RouterPort.NO_AS ||
                !port.bgpNetworkIds.isEmpty) {
                // Cannot create a BGP peering on a port that already has an AS
                // number set, or one or more BGP networks.
                Response.status(Status.CONFLICT).build()
            } else {
                bgp.create(portId, bgp.localAS)
                multiResource(Seq(Update(port), Create(bgp)),
                              Response.created(bgp.getUri).build())
            }
        }).getOrThrow
    }

}