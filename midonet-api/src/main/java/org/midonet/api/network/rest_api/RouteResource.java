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
package org.midonet.api.network.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.network.Route;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.auth.RouteAuthorizer;
import org.midonet.api.network.auth.RouterAuthorizer;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.event.topology.RouterEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for ports.
 */
@RequestScoped
public class RouteResource extends AbstractResource {
    /*
     * Implements REST API endpoints for routes.
     */

    private final static RouterEvent routerEvent = new RouterEvent();

    private final RouteAuthorizer authorizer;

    @Inject
    public RouteResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, RouteAuthorizer authorizer,
                         DataClient dataClient) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
    }

    /**
     * Handler to deleting a route.
     *
     * @param id
     *            Route ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.Route routeData =
                dataClient.routesGet(id);
        if (routeData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this route.");
        }

        dataClient.routesDelete(id);
        routerEvent.routeDelete(routeData.getRouterId(), id);
    }

    /**
     * Handler to getting a route.
     *
     * @param id
     *            Route ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Route object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Route get(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this route.");
        }

        org.midonet.cluster.data.Route routeData =
                dataClient.routesGet(id);
        if (routeData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Route route = new Route(routeData);
        route.setBaseUri(getBaseUri());

        return route;
    }

    /**
     * Sub-resource class for router's route.
     */
    @RequestScoped
    public static class RouterRouteResource extends AbstractResource {

        private final UUID routerId;
        private final RouterAuthorizer authorizer;

        @Inject
        public RouterRouteResource(RestApiConfig config,
                                   UriInfo uriInfo,
                                   SecurityContext context,
                                   RouterAuthorizer authorizer,
                                   Validator validator,
                                   DataClient dataClient,
                                   @Assisted UUID routerId) {
            super(config, uriInfo, context, dataClient, validator);
            this.routerId = routerId;
            this.authorizer = authorizer;
        }

        /**
         * Handler for creating a router route.
         *
         * @param route
         *            Route object.
         * @throws StateAccessException
         *             Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Route route)
                throws StateAccessException,
                SerializationException {

            route.setRouterId(routerId);
            validate(route, Route.RouteGroupSequence.class);

            if (!authorizer.authorize(context, AuthAction.WRITE, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add route to this router.");
            }

            UUID id = dataClient.routesCreate(route.toData());
            routerEvent.routeCreate(routerId, dataClient.routesGet(id));
            return Response.created(
                    ResourceUriBuilder.getRoute(getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list routes.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Route objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Route> list()
                throws StateAccessException,
                SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these routes.");
            }

            List<org.midonet.cluster.data.Route> routeDataList =
                    dataClient.routesFindByRouter(routerId);
            List<Route> routes = new ArrayList<>();
            if (routeDataList != null) {

                for (org.midonet.cluster.data.Route routeData :
                        routeDataList) {
                    Route route = new Route(routeData);
                    route.setBaseUri(getBaseUri());
                    routes.add(route);
                }

            }

            return routes;
        }
    }
}
