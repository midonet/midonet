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
import org.midonet.cluster.rest_api.neutron.models.Pool;
import org.midonet.cluster.rest_api.neutron.models.PoolHealthMonitor;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class PoolResource {

    private final LoadBalancerApi api;
    private final URI baseUri;

    @Inject
    public PoolResource(UriInfo uriInfo, LoadBalancerApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.POOL_JSON_V1)
    public Pool get(@PathParam("id") UUID id) {
        Pool pool = api.getPool(id);
        if (pool == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id));
        }
        return pool;
    }

    @GET
    @Produces(NeutronMediaType.POOLS_JSON_V1)
    public List<Pool> list() {
        return api.getPools();
    }

    @POST
    @Consumes(NeutronMediaType.POOL_JSON_V1)
    @Produces(NeutronMediaType.POOL_JSON_V1)
    public Response create(Pool pool) {
        api.createPool(pool);
        return Response.created(
            NeutronUriBuilder.getPool(baseUri, pool.id))
            .entity(pool).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deletePool(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.POOL_JSON_V1)
    @Produces(NeutronMediaType.POOL_JSON_V1)
    public Response update(@PathParam("id") UUID id, Pool pool) {
        api.updatePool(id, pool);
        return Response.ok(NeutronUriBuilder.getPool(baseUri, pool.id))
                       .entity(pool).build();
    }

    @POST
    @Path("/{id}/health_monitors")
    @Consumes(NeutronMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    public final Response create(@PathParam("id") UUID poolId,
                                 PoolHealthMonitor poolHealthMonitor) {
        api.createPoolHealthMonitor(poolId, poolHealthMonitor);
        return Response.created(NeutronUriBuilder.getPoolHealthMonitor(baseUri))
                       .entity(poolHealthMonitor).build();
    }

    @DELETE
    @Path("{id}/health_monitors/{hmId}")
    public final void delete(@PathParam("id") UUID poolId,
                             @PathParam("hmId") UUID hmId) {
        api.deletePoolHealthMonitor(poolId, hmId);
    }
}
