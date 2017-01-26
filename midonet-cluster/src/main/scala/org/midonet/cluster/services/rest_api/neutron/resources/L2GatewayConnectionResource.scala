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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.net.URI
import java.util
import java.util.UUID

import javax.ws.rs._
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.L2GatewayConnection
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.L2GatewayConnectionApi

class L2GatewayConnectionResource @Inject()(uriInfo: UriInfo,
                                            private val api: L2GatewayConnectionApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1))
    def get(@PathParam("id") id: UUID): L2GatewayConnection = {
        api.getL2GatewayConnection(id)
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTIONS_JSON_V1))
    def list: util.List[L2GatewayConnection] = api.getL2GatewayConnections

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1))
    def create(cnxn: L2GatewayConnection): Response = {
        api.createL2GatewayConnection(cnxn)
        Response.created(
            NeutronUriBuilder.getL2GatewayConn(baseUri, cnxn.id))
            .entity(cnxn).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1))
    def update(@PathParam("id") id: UUID,
               cnxn: L2GatewayConnection): Response = {
        if (cnxn.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateL2GatewayConnection(cnxn)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteL2GatewayConnection(id)
        Response.noContent().build()
    }
}
