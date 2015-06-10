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

import java.util
import java.util.UUID
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs.{Consumes, DELETE, GET, POST, PUT, Path, PathParam, Produces}

import com.google.inject.Inject

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{VIPS, getUri}
import org.midonet.cluster.rest_api.neutron.models.VIP
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.LBMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class VipResource @Inject()(uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @GET
    @Path("{id}")
    @Produces(Array(LBMediaType.VIP_JSON_V1))
    def get(@PathParam("id") id: UUID): VIP = {
        val vip = api.getVip(id)
        if (vip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        vip
    }

    @GET
    @Produces(Array(LBMediaType.VIPS_JSON_V1))
    def list: util.List[VIP] = api.getVips

    @POST
    @Consumes(Array(LBMediaType.VIP_JSON_V1))
    @Produces(Array(LBMediaType.VIP_JSON_V1))
    def create(vip: VIP): Response = {
        api.createVip(vip)
        Response.created(getUri(uriInfo.getBaseUri, VIPS, vip.id))
                .entity(vip).build
    }

    @DELETE
    @Path("{id}") def delete(@PathParam("id") id: UUID) {
        api.deleteVip(id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(LBMediaType.VIP_JSON_V1))
    @Produces(Array(LBMediaType.VIP_JSON_V1))
    def update(@PathParam("id") id: UUID, vip: VIP): Response = {
        api.updateVip(id, vip)
        Response.ok(getUri(uriInfo.getBaseUri, VIPS, vip.id)).entity(vip)
                .build
    }
}