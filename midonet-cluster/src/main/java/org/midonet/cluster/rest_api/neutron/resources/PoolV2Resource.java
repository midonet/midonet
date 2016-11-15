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

import com.google.inject.Inject;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.PoolV2;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerV2Api;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class PoolV2Resource {

    private final LoadBalancerV2Api api;
    private final URI baseUri;

    @Inject
    public PoolV2Resource(UriInfo uriInfo, LoadBalancerV2Api api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.POOL_JSON_V2)
    public PoolV2 get(@PathParam("id") UUID id) {
        PoolV2 pool = api.getPoolV2(id);
        if (pool == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return pool;
    }

    @GET
    @Produces(NeutronMediaType.POOLS_JSON_V2)
    public List<PoolV2> list() {
        return api.getPoolsV2();
    }

    @POST
    @Consumes(NeutronMediaType.POOL_JSON_V2)
    @Produces(NeutronMediaType.POOL_JSON_V2)
    public Response create(PoolV2 pool) {
        api.createPoolV2(pool);
        return Response.created(
            NeutronUriBuilder.getPoolV2(baseUri, pool.id))
            .entity(pool).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deletePoolV2(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.POOL_JSON_V2)
    @Produces(NeutronMediaType.POOL_JSON_V2)
    public Response update(@PathParam("id") UUID id, PoolV2 pool) {
        api.updatePoolV2(id, pool);
        return Response.ok(NeutronUriBuilder.getPoolV2(baseUri, pool.id))
                       .entity(pool).build();
    }
}
