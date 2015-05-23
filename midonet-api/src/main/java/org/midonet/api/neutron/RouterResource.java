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
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.L3Api;
import org.midonet.cluster.data.neutron.Router;
import org.midonet.cluster.data.neutron.RouterInterface;
import org.midonet.event.neutron.RouterEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.cluster.rest_api.validation.MessageProperty.*;
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
    public Response create(Router router)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("RouterResource.create entered {}", router);

        try {

            Router r = api.createRouter(router);
            ROUTER_EVENT.create(r.id, r);
            log.info("RouterResource.create exiting {}", r);
            return Response.created(
                    NeutronUriBuilder.getRouter(
                            getBaseUri(), r.id)).entity(r).build();

        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.delete entered {}", id);
        api.deleteRouter(id);
        ROUTER_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Router get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
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
    public List<Router> list()
            throws SerializationException, StateAccessException {
        log.info("RouterResource.list entered");
        return api.getRouters();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.ROUTER_JSON_V1)
    @Produces(NeutronMediaType.ROUTER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Router router)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("RouterResource.update entered {}", router);

        try {

            Router r = api.updateRouter(id, router);
            ROUTER_EVENT.update(id, r);
            log.info("RouterResource.update exiting {}", r);
            return Response.ok(
                    NeutronUriBuilder.getRouter(
                            getBaseUri(), r.id)).entity(r).build();

        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.ADD_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void addRouterInterface(@PathParam("id") UUID id,
                                   RouterInterface intf)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.addRouterInterface entered {}", intf);

        try {

            RouterInterface ri = api.addRouterInterface(id, intf);
            ROUTER_EVENT.interfaceUpdate(ri.id, ri);
            log.info("RouterResource.addRouterInterface exiting {}", ri);
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }

    @PUT
    @Path("{id}" + NeutronUriBuilder.REMOVE_ROUTER_INTF)
    @Consumes(NeutronMediaType.ROUTER_INTERFACE_V1)
    @Produces(NeutronMediaType.ROUTER_INTERFACE_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public void removeRouterInterface(@PathParam("id") UUID id,
                                      RouterInterface intf)
            throws SerializationException, StateAccessException {
        log.info("RouterResource.removeRouterInterface entered {}", intf);
        RouterInterface ri = api.removeRouterInterface(id, intf);
        ROUTER_EVENT.interfaceUpdate(ri.id, ri);
        log.info("RouterResource.removeRouterInterface exiting {}", ri);
    }
}
