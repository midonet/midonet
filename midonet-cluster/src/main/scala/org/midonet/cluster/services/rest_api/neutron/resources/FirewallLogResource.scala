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
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder
import org.midonet.cluster.rest_api.neutron.models.FirewallLog
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.FirewallLoggingApi

class FirewallLogResource @Inject()(uriInfo: UriInfo,
                                   private val api: FirewallLoggingApi) {
    private val baseUri = uriInfo.getBaseUri

    @POST
    @Consumes(Array(MidonetMediaTypes.NEUTRON_FIREWALL_LOG_JSON_V1))
    @Produces(Array(MidonetMediaTypes.NEUTRON_FIREWALL_LOG_JSON_V1))
    def create(entry: FirewallLog): Response = {
        api.createFirewallLog(entry)
        Response.created(
            NeutronUriBuilder.getFirewallLog(baseUri, entry.id))
            .entity(entry).build()
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(MidonetMediaTypes.NEUTRON_FIREWALL_LOG_JSON_V1))
    def update(@PathParam("id") id: UUID,
               fwl: FirewallLog): Response = {
        if (fwl.id != id) {
            throw new BadRequestHttpException("Path ID does not match object ID")
        }
        api.updateFirewallLog(fwl)
        Response.noContent().build()
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID): Response = {
        api.deleteFirewallLog(id)
        Response.noContent().build()
    }
}
