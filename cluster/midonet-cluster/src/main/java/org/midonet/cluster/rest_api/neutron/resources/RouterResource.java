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
import org.midonet.cluster.rest_api.neutron.models.Router;
import org.midonet.cluster.rest_api.neutron.models.RouterInterface;
import org.midonet.cluster.services.rest_api.neutron.plugin.L3Api;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class RouterResource {

    private final L3Api api;
    private final URI baseUri;

    @Inject
    public RouterResource(UriInfo uriInfo, L3Api api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    public Response create(Router router) {
        Router r = api.createRouter(router);
        return Response.created(NeutronUriBuilder.getRouter(baseUri,
                                                            r.id))
                       .entity(r).build();

    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteRouter(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    public Router get(@PathParam("id") UUID id) {
        Router r = api.getRouter(id);
        if (r == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return r;
    }

    @GET
    @Produces(NeutronMediaType.ROUTERS_JSON_V1)
    public List<Router> list() {
        return api.getRouters();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    public Response update(@PathParam("id") UUID id, Router router) {
        Router r = api.updateRouter(id, router);
        return Response.ok(NeutronUriBuilder.getRouter(baseUri, r.id))
                       .entity(r).build();

    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.ADD_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    public void addRouterInterface(@PathParam("id") UUID id,
                                   RouterInterface intf) {
        RouterInterface ri = api.addRouterInterface(id, intf);
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.REMOVE_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    public void removeRouterInterface(@PathParam("id") UUID id,
                                      RouterInterface intf) {
        api.removeRouterInterface(id, intf);
    }
}
