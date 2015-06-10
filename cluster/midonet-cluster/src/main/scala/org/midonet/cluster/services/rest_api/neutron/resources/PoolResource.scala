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
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{POOLS, POOL_HEALTH_MONITOR, getUri}
import org.midonet.cluster.rest_api.neutron.models.{Pool, PoolHealthMonitor}
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.LBMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class PoolResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @GET
    @Path("{id}")
    @Produces(Array(LBMediaType.POOL_JSON_V1))
    def get(@PathParam("id") id: UUID): Pool = {
        val pool: Pool = api.getPool(id)
        if (pool == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        }
        pool
    }

    @GET
    @Produces(Array(LBMediaType.POOLS_JSON_V1))
    def list: util.List[Pool] = api.getPools

    @POST
    @Consumes(Array(LBMediaType.POOL_JSON_V1))
    @Produces(Array(LBMediaType.POOL_JSON_V1))
    def create(pool: Pool): Response = {
        api.createPool(pool)
        Response.created(getUri(uriInfo.getBaseUri, POOLS, pool.id))
                .entity(pool).build
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID) {
        api.deletePool(id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(LBMediaType.POOL_JSON_V1))
    @Produces(Array(LBMediaType.POOL_JSON_V1))
    def update(@PathParam("id") id: UUID, pool: Pool): Response = {
        api.updatePool(id, pool)
        Response.ok(getUri(uriInfo.getBaseUri, POOLS, pool.id))
                .entity(pool).build
    }

    @POST
    @Path("/{id}/health_monitors")
    @Consumes(Array(LBMediaType.POOL_HEALTH_MONITOR_JSON_V1))
    @Produces(Array(LBMediaType.POOL_HEALTH_MONITOR_JSON_V1))
    final def create(@PathParam("id") poolId: UUID,
                     phm: PoolHealthMonitor): Response = {
        api.createPoolHealthMonitor(poolId, phm)
        Response.created(getUri(uriInfo.getBaseUri, POOL_HEALTH_MONITOR))
                .entity(phm).build
    }

    @DELETE
    @Path("{id}/health_monitors/{hmId}")
    final def delete(@PathParam("id") poolId: UUID,
                     @PathParam("hmId") hmId: UUID) {
        api.deletePoolHealthMonitor(poolId, hmId)
    }
}