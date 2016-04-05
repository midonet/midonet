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

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.TapFlow
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.TapAsAServiceApi

class TapFlowResource @Inject()(uriInfo: UriInfo,
                                private val api: TapAsAServiceApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_TAP_FLOW_JSON_V1))
    def get(@PathParam("id") id: UUID): TapFlow = {
        api.getTapFlow(id)
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_TAP_FLOWS_JSON_V1))
    def list: util.List[TapFlow] = api.getTapFlows

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_TAP_FLOW_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_TAP_FLOW_JSON_V1))
    def create(cnxn: TapFlow): Response = {
        api.createTapFlow(cnxn)
        Response.created(
            NeutronUriBuilder.getTapFlow(baseUri, cnxn.id))
            .entity(cnxn).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_TAP_FLOW_JSON_V1))
    def update(@PathParam("id") id: UUID,
               cnxn: TapFlow): Response = {
        if (cnxn.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateTapFlow(cnxn)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteTapFlow(id)
        Response.noContent().build()
    }
}
