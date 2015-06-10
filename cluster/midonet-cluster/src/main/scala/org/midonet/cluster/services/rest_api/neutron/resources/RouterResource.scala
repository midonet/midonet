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

import org.midonet.cluster.data.neutron.RouterInterface
import org.midonet.cluster.data.neutron.models.RouterInterface
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{ADD_ROUTER_INTF, REMOVE_ROUTER_INTF, ROUTERS, getUri}
import org.midonet.cluster.rest_api.neutron.models.{RouterInterface, Router}
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class RouterResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.ROUTER_JSON_V1))
    @Produces(Array(NeutronMediaType.ROUTER_JSON_V1))
    def create(router: Router): Response = {
        val r: Router = api.createRouter(router)
        Response.created(getUri(uriInfo.getBaseUri, ROUTERS, r.id))
                .entity(r).build
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: UUID) {
        api.deleteRouter(id)
    }

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.ROUTER_JSON_V1))
    def get(@PathParam("id") id: UUID): Router = {
        val r: Router = api.getRouter(id)
        if (r == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        r
    }

    @GET
    @Produces(Array(NeutronMediaType.ROUTERS_JSON_V1))
    def list: util.List[Router] = {
        api.getRouters
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.ROUTER_JSON_V1))
    @Produces(Array(NeutronMediaType.ROUTER_JSON_V1))
    def update(@PathParam("id") id: UUID, router: Router): Response = {
        val r: Router = api.updateRouter(id, router)
        Response.ok(getUri(uriInfo.getBaseUri, ROUTERS, r.id)).entity(r)
                .build
    }

    @PUT
    @Path("{id}" + ADD_ROUTER_INTF)
    @Consumes(Array(NeutronMediaType.ROUTER_INTERFACE_V1))
    @Produces(Array(NeutronMediaType.ROUTER_INTERFACE_V1))
    def addRouterInterface(@PathParam("id") id: UUID, intf: RouterInterface)
    : Unit = {
        api.addRouterInterface(id, intf)
    }

    @PUT
    @Path("{id}" + REMOVE_ROUTER_INTF)
    @Consumes(Array(NeutronMediaType.ROUTER_INTERFACE_V1))
    @Produces(Array(NeutronMediaType
                        .ROUTER_INTERFACE_V1))
    def removeRouterInterface(@PathParam("id") id: UUID,
                              intf: RouterInterface): Unit = {
        api.removeRouterInterface(id, intf)
    }
}