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

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.VpnService
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.VpnServiceApi

class VpnServiceResource @Inject()(uriInfo: UriInfo,
                                   private val api: VpnServiceApi) {

    private val baseUri: URI = uriInfo.getBaseUri

    @GET
    @Path("{id}")
    @Produces(Array(MidonetMediaTypes.NEUTRON_VPN_SERVICE_JSON_V1))
    def get(@PathParam("id") id: UUID): VpnService = {
        val vpn = api.getVpnService(id)
        if (vpn == null)
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        vpn
    }

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_VPN_SERVICES_JSON_V1))
    def list: util.List[VpnService] = api.getVpnServices

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_VPN_SERVICE_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_VPN_SERVICE_JSON_V1))
    def create(vpn: VpnService): Response = {
        api.createVpnService(vpn)
        Response.created(
            NeutronUriBuilder.getVpnService(baseUri, vpn.id))
            .entity(vpn).build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteVpnService(id)
        Response.noContent().build()
    }
}
