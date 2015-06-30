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
package org.midonet.api.neutron;

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

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Router;
import org.midonet.cluster.rest_api.neutron.models.RouterInterface;
import org.midonet.cluster.services.rest_api.neutron.plugin.L3Api;
import org.midonet.event.neutron.RouterEvent;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.slf4j.LoggerFactory.getLogger;

public class RouterResource extends AbstractResource {

    private final static Logger log = getLogger(RouterResource.class);
    private final static RouterEvent ROUTER_EVENT = new RouterEvent();
    private final L3Api api;

    @Inject
    public RouterResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, L3Api api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Router router) {
        log.info("RouterResource.create entered {}", router);

        Router r = api.createRouter(router);
        ROUTER_EVENT.create(r.id, r);
        log.info("RouterResource.create exiting {}", r);
        return Response.created(
            NeutronUriBuilder.getRouter(
                getBaseUri(), r.id)).entity(r).build();

    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("RouterResource.delete entered {}", id);
        api.deleteRouter(id);
        ROUTER_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Router get(@PathParam("id") UUID id) {
        log.info("RouterResource.get entered {}", id);

        Router r = api.getRouter(id);
        if (r == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("RouterResource.get exiting {}", r);
        return r;
    }

    @GET
    @Produces(NeutronMediaType.ROUTERS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Router> list() {
        log.info("RouterResource.list entered");
        return api.getRouters();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Router router) {
        log.info("RouterResource.update entered {}", router);

        Router r = api.updateRouter(id, router);
        ROUTER_EVENT.update(id, r);
        log.info("RouterResource.update exiting {}", r);
        return Response.ok(
            NeutronUriBuilder.getRouter(
                getBaseUri(), r.id)).entity(r).build();

    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.ADD_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void addRouterInterface(@PathParam("id") UUID id,
                                   RouterInterface intf) {
        log.info("RouterResource.addRouterInterface entered {}", intf);

        RouterInterface ri = api.addRouterInterface(id, intf);
        ROUTER_EVENT.interfaceUpdate(ri.id, ri);
        log.info("RouterResource.addRouterInterface exiting {}", ri);
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.REMOVE_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void removeRouterInterface(@PathParam("id") UUID id,
                                      RouterInterface intf) {
        log.info("RouterResource.removeRouterInterface entered {}", intf);
        RouterInterface ri = api.removeRouterInterface(id, intf);
        ROUTER_EVENT.interfaceUpdate(ri.id, ri);
        log.info("RouterResource.removeRouterInterface exiting {}", ri);
    }
}
