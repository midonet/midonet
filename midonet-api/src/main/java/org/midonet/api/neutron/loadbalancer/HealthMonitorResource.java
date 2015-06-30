/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.api.neutron.loadbalancer;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.models.HealthMonitor;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;
import org.midonet.event.neutron.HealthMonitorEvent;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class HealthMonitorResource extends AbstractResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        HealthMonitorResource.class);
    private static final HealthMonitorEvent HEALTH_MONITOR_EVENT =
        new HealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public HealthMonitor get(@PathParam("id") UUID id) {
        LOG.info("HealthMonitorResource.get entered {}", id);

        HealthMonitor healthMonitor = api.getHealthMonitor(id);
        if (healthMonitor == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        LOG.info("HealthMonitorResource.get exiting {}", healthMonitor);
        return healthMonitor;
    }

    @GET
    @Produces(NeutronMediaType.HEALTH_MONITORS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<HealthMonitor> list() {
        LOG.info("HealthMonitorResource.list entered");
        return api.getHealthMonitors();
    }

    @POST
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response create(HealthMonitor healthMonitor) {
        LOG.info("HealthMonitorResource.create entered {}", healthMonitor);

        api.createHealthMonitor(healthMonitor);
        HEALTH_MONITOR_EVENT.create(healthMonitor.id, healthMonitor);
        LOG.info("HealthMonitorResource.create exiting {}", healthMonitor);
        return Response.created(
            LBUriBuilder.getHealthMonitor(getBaseUri(), healthMonitor.id))
            .entity(healthMonitor).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public final void delete(@PathParam("id") UUID id) {
        LOG.info("HealthMonitorResource.delete entered {}", id);
        api.deleteHealthMonitor(id);
        HEALTH_MONITOR_EVENT.delete(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response update(@PathParam("id") UUID id,
                                 HealthMonitor healthMonitor) {
        LOG.info("HealthMonitorResource.update entered {}", healthMonitor);

        api.updateHealthMonitor(id, healthMonitor);
        HEALTH_MONITOR_EVENT.update(id, healthMonitor);
        LOG.info("HealthMonitorResource.update exiting {}", healthMonitor);
        return Response.ok(
            NeutronUriBuilder.getNetwork(getBaseUri(), healthMonitor.id))
            .entity(healthMonitor).build();
    }
}
