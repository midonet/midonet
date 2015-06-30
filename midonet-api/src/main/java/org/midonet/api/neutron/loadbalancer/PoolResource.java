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
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.models.Pool;
import org.midonet.cluster.rest_api.neutron.models.PoolHealthMonitor;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;
import org.midonet.event.neutron.PoolEvent;
import org.midonet.event.neutron.PoolHealthMonitorEvent;

public class PoolResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        PoolResource.class);
    private final static PoolEvent POOL_EVENT = new PoolEvent();
    private static final PoolHealthMonitorEvent POOL_HEALTH_MONITOR_EVENT
        = new PoolHealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public PoolResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Pool get(@PathParam("id") UUID id) {
        log.info("PoolResource.get entered {}", id);

        Pool pool = api.getPool(id);
        if (pool == null) {
            throw notFoundException(id, "pool");
        }

        log.info("PoolResource.get exiting {}", pool);
        return pool;
    }

    @GET
    @Produces(NeutronMediaType.POOLS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Pool> list() {
        log.info("PoolResource.list entered");
        return api.getPools();
    }

    @POST
    @Consumes(NeutronMediaType.POOL_JSON_V1)
    @Produces(NeutronMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Pool pool) {
        log.info("PoolResource.create entered {}", pool);

        api.createPool(pool);
        POOL_EVENT.create(pool.id, pool);
        log.info("PoolResource.create exiting {}", pool);
        return Response.created(
            LBUriBuilder.getPool(getBaseUri(), pool.id))
            .entity(pool).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("PoolResource.delete entered {}", id);
        api.deletePool(id);
        POOL_EVENT.delete(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.POOL_JSON_V1)
    @Produces(NeutronMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Pool pool) {
        log.info("PoolResource.update entered {}", pool);

        api.updatePool(id, pool);
        POOL_EVENT.update(id, pool);
        log.info("PoolResource.update exiting {}", pool);
        return Response.ok(
            LBUriBuilder.getPool(getBaseUri(), pool.id))
            .entity(pool).build();
    }

    @POST
    @Path("/{id}/health_monitors")
    @Consumes(NeutronMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response create(@PathParam("id") UUID poolId,
                                 PoolHealthMonitor poolHealthMonitor) {
        log.info("PoolHealthMonitorResource.create entered {}",
                 poolHealthMonitor);

        api.createPoolHealthMonitor(poolId, poolHealthMonitor);
        POOL_HEALTH_MONITOR_EVENT.create(poolId, poolHealthMonitor.id);
        log.info("PoolHealthMonitorResource.create exiting {} {}", poolId,
                 poolHealthMonitor);
        return Response.created(
            LBUriBuilder.getPoolHealthMonitor(getBaseUri()))
            .entity(poolHealthMonitor).build();
    }

    @DELETE
    @Path("{id}/health_monitors/{hmId}")
    @RolesAllowed(AuthRole.ADMIN)
    public final void delete(@PathParam("id") UUID poolId,
                             @PathParam("hmId") UUID hmId) {
        log.info("PoolHealthMonitorResource.delete entered {} {}",
                 poolId, hmId);
        api.deletePoolHealthMonitor(poolId, hmId);
        POOL_HEALTH_MONITOR_EVENT.delete(poolId, hmId);
    }
}
