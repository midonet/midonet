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
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{HEALTH_MONITORS, getUri}
import org.midonet.cluster.rest_api.neutron.models.HealthMonitor
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.LBMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class HealthMonitorResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @GET
    @Path("{id}")
    @Produces(Array(LBMediaType.HEALTH_MONITOR_JSON_V1))
    def get(@PathParam("id") id: UUID): HealthMonitor = {
        val healthMonitor: HealthMonitor = api.getHealthMonitor(id)
        if (healthMonitor == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        healthMonitor
    }

    @GET
    @Produces(Array(LBMediaType.HEALTH_MONITORS_JSON_V1))
    def list: util.List[HealthMonitor] = {
        api.getHealthMonitors
    }

    @POST
    @Consumes(Array(LBMediaType.HEALTH_MONITOR_JSON_V1))
    @Produces(Array(LBMediaType.HEALTH_MONITOR_JSON_V1))
    final def create(healthMonitor: HealthMonitor): Response = {
        api.createHealthMonitor(healthMonitor)
        Response.created(getUri(uriInfo.getBaseUri, HEALTH_MONITORS,
                                healthMonitor.id))
                .entity(healthMonitor).build
    }

    @DELETE
    @Path("{id}")
    final def delete(@PathParam("id") id: UUID) {
        api.deleteHealthMonitor(id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(LBMediaType.HEALTH_MONITOR_JSON_V1))
    @Produces(Array(LBMediaType.HEALTH_MONITOR_JSON_V1))
    final def update(@PathParam("id") id: UUID, healthMonitor: HealthMonitor)
    : Response = {
        api.updateHealthMonitor(id, healthMonitor)
        Response.ok(getUri(uriInfo.getBaseUri, HEALTH_MONITORS,
                           healthMonitor.id))
                .entity(healthMonitor).build
    }
}