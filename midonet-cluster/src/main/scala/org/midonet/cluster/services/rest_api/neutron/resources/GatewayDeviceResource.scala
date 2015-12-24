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

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.GatewayDevice
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.GatewayDeviceApi

class GatewayDeviceResource @Inject()(uriInfo: UriInfo,
                                      private val api: GatewayDeviceApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1))
    def get(@PathParam("id") id: UUID): GatewayDevice = {
        api.getGatewayDevice(id)
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_GATEWAY_DEVICES_JSON_V1))
    def list: util.List[GatewayDevice] = api.getGatewayDevices

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1))
    def create(cnxn: GatewayDevice): Response = {
        api.createGatewayDevice(cnxn)
        Response.created(
            NeutronUriBuilder.getGatewayDevice(baseUri, cnxn.id))
            .entity(cnxn).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1))
    def update(@PathParam("id") id: UUID,
               cnxn: GatewayDevice): Response = {
        if (cnxn.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateGatewayDevice(cnxn)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteGatewayDevice(id)
        Response.noContent().build()
    }
}
