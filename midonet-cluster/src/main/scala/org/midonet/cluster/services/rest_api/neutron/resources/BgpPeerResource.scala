/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.net.URI
import java.util
import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.BgpPeer
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.BgpApi

class BgpPeerResource @Inject()(uriInfo: UriInfo,
                                private val api: BgpApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_PEER_JSON_V1))
    def get(@PathParam("id") id: UUID): BgpPeer = {
        val bgpPeer = api.getBgpPeer(id)
        if (bgpPeer == null)
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        bgpPeer
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_PEERS_JSON_V1))
    def list: util.List[BgpPeer] = api.getBgpPeers

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_BGP_PEER_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_PEER_JSON_V1))
    def create(bgpPeer: BgpPeer): Response = {
        api.createBgpPeer(bgpPeer)
        Response.created(
            NeutronUriBuilder.getBgpPeer(baseUri, bgpPeer.id))
            .entity(bgpPeer).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_BGP_PEER_JSON_V1))
    def update(@PathParam("id") id: UUID, bgpPeer: BgpPeer): Response = {
        if (bgpPeer.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateBgpPeer(bgpPeer)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteBgpPeer(id)
        Response.noContent().build()
    }
}
