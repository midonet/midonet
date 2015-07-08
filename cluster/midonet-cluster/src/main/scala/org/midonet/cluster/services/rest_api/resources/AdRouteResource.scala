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

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.InternalServerErrorHttpException
import org.midonet.cluster.rest_api.annotation.{AllowDelete, AllowCreate}
import org.midonet.cluster.rest_api.models.{Router, AdRoute, Bgp, RouterPort}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowDelete
class AdRouteResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[AdRoute](resContext) {

    @GET
    @Produces(Array(APPLICATION_AD_ROUTE_JSON,
                    APPLICATION_JSON))
    @Path("{id}")
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String): AdRoute = {
        getResource(classOf[AdRoute], id).flatMap(adRoute => {
            getResource(classOf[Router], adRoute.routerId).map(router => {
                if ((router.bgpPeerIds eq null) || router.bgpPeerIds.size != 1) {
                    throw new InternalServerErrorHttpException(
                        s"Orphaned BGP route $id: route ${adRoute.routerId} " +
                        "does not have a BGP peer")
                }
                // The legacy v1 API only supports the addition of one BGP peer
                // due to the limitation of adding a single local AS, which is
                // stored in the BGP object for a router port.
                //
                // In the compatibility API, this limitation is enforced in the
                // create method of the PortBgpResource.
                adRoute.bgpId = router.bgpPeerIds.get(0)
                adRoute
            })
        }).getOrThrow
    }

}

@RequestScoped
class BgpAdRouteResource @Inject()(bgpId: UUID, resContext: ResourceContext)
    extends MidonetResource[AdRoute](resContext) {


    @GET
    @Produces(Array(APPLICATION_AD_ROUTE_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[AdRoute] = {
        getResource(classOf[Bgp], bgpId).flatMap(bgp => {
            getResource(classOf[Router], bgp.routerId).flatMap(router => {
                listResources(classOf[AdRoute], router.bgpNetworkIds.asScala)
                    .map(list => {
                    list.foreach(adRoute => {
                        adRoute.bgpId = bgpId
                    })
                    list
                })
            })
        }).getOrThrow.asJava
    }

    @POST
    @Consumes(Array(APPLICATION_AD_ROUTE_JSON,
                    APPLICATION_JSON))
    override def create(adRoute: AdRoute,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        adRoute.setBaseUri(resContext.uriInfo.getBaseUri)
        throwIfViolationsOn(adRoute)

        getResource(classOf[Bgp], bgpId).map(bgp => {
            adRoute.create(bgpId, bgp.routerId)
            createResource(adRoute)
        }).getOrThrow
    }

}