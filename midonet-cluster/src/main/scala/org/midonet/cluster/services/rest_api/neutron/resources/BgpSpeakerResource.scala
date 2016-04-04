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
import org.midonet.cluster.rest_api.neutron.models.BgpSpeaker
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.BgpApi

class BgpSpeakerResource @Inject()(uriInfo: UriInfo,
                                   private val api: BgpApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_SPEAKER_JSON_V1))
    def get(@PathParam("id") id: UUID): BgpSpeaker = {
        val bgpSpeaker = api.getBgpSpeaker(id)
        if (bgpSpeaker == null)
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        bgpSpeaker
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_SPEAKERS_JSON_V1))
    def list: util.List[BgpSpeaker] = api.getBgpSpeakers

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_BGP_SPEAKER_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_BGP_SPEAKER_JSON_V1))
    def create(bgpSpeaker: BgpSpeaker): Response = {
        api.createBgpSpeaker(bgpSpeaker)
        Response.created(
            NeutronUriBuilder.getBgpSpeaker(baseUri, bgpSpeaker.id))
            .entity(bgpSpeaker).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_BGP_SPEAKER_JSON_V1))
    def update(@PathParam("id") id: UUID, bgpSpeaker: BgpSpeaker): Response = {
        if (bgpSpeaker.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateBgpSpeaker(bgpSpeaker)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteBgpSpeaker(id)
        Response.noContent().build()
    }
}
