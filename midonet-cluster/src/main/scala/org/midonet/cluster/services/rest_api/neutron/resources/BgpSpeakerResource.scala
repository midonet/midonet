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

import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.neutron.models.BgpSpeaker
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.BgpApi

class BgpSpeakerResource @Inject()(uriInfo: UriInfo,
                                   private val api: BgpApi) {

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
}
