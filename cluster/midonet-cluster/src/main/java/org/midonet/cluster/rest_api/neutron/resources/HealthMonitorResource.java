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
package org.midonet.cluster.rest_api.neutron.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.HealthMonitor;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class HealthMonitorResource {

    private final LoadBalancerApi api;
    private final URI baseUri;

    @Inject
    public HealthMonitorResource(UriInfo uriInfo, LoadBalancerApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    public HealthMonitor get(@PathParam("id") UUID id) {
        HealthMonitor healthMonitor = api.getHealthMonitor(id);
        if (healthMonitor == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return healthMonitor;
    }

    @GET
    @Produces(NeutronMediaType.HEALTH_MONITORS_JSON_V1)
    public List<HealthMonitor> list() {
        return api.getHealthMonitors();
    }

    @POST
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    public final Response create(HealthMonitor healthMonitor) {

        api.createHealthMonitor(healthMonitor);
        return Response.created(NeutronUriBuilder
                                    .getHealthMonitor(baseUri,
                                                      healthMonitor.id))
                        .entity(healthMonitor).build();
    }

    @DELETE
    @Path("{id}")
    public final void delete(@PathParam("id") UUID id) {
        api.deleteHealthMonitor(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    public final Response update(@PathParam("id") UUID id,
                                 HealthMonitor healthMonitor) {

        api.updateHealthMonitor(id, healthMonitor);
        return Response.ok(NeutronUriBuilder.getNetwork(baseUri,
                                                        healthMonitor.id))
                       .entity(healthMonitor).build();
    }
}
