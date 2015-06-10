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
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{FLOATING_IPS, getUri}
import org.midonet.cluster.rest_api.neutron.models.FloatingIp
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class FloatingIpResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.FLOATING_IP_JSON_V1))
    @Produces(Array(NeutronMediaType.FLOATING_IP_JSON_V1))
    def create(floatingIp: FloatingIp): Response = {
        val fip = api.createFloatingIp(floatingIp)
        Response.created(getUri(uriInfo.getBaseUri, FLOATING_IPS, fip.id))
                .entity(fip).build
    }

    @DELETE
    @Path("{id}") def delete(@PathParam("id") id: UUID): Unit =
        api.deleteFloatingIp(id)

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.FLOATING_IP_JSON_V1))
    def get(@PathParam("id") id: UUID): FloatingIp = {
        val fip = api.getFloatingIp(id)
        if (fip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        fip
    }

    @GET
    @Produces(Array(NeutronMediaType.FLOATING_IPS_JSON_V1))
    def list: util.List[FloatingIp] = api.getFloatingIps

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.FLOATING_IP_JSON_V1))
    @Produces(Array(NeutronMediaType.FLOATING_IP_JSON_V1))
    def update(@PathParam("id") id: UUID, floatingIp: FloatingIp): Response = {
        val fip = api.updateFloatingIp(id, floatingIp)
        Response.ok(getUri(uriInfo.getBaseUri, FLOATING_IPS, fip.id))
                .entity(fip).build
    }
}