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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.RouteDataConverter;
import org.midonet.cluster.rest_api.models.Route;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.event.topology.RouterEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class RouteResource extends AbstractResource {

    private final static RouterEvent routerEvent = new RouterEvent();

    @Inject
    public RouteResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, DataClient dataClient) {
        super(config, uriInfo, context, dataClient, null);
    }

    /**
     * Handler to deleting a route.
     *
     * @param id Route ID from the request.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.Route routeData = dataClient.routesGet(id);
        if (routeData == null) {
            return;
        }

        authoriser.tryAuthoriseRouter(routeData.getRouterId(),
                                      "delete this route");

        dataClient.routesDelete(id);
        routerEvent.routeDelete(routeData.getRouterId(), id);
    }

    /**
     * Handler to getting a route.
     *
     * @param id Route ID from the request.
     * @throws StateAccessException Data access error.
     * @return A Route object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Route get(@PathParam("id") UUID id) throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.Route routeData = dataClient.routesGet(id);

        if (routeData == null || routeData.getRouterId() == null) {
            notFoundException(id, "route");
        }

        authoriser.tryAuthoriseRouter(routeData.getRouterId(),
                                      "view this route");

        return RouteDataConverter.fromData(routeData, getBaseUri());
    }

    /**
     * Sub-resource class for router's route.
     */
    @RequestScoped
    public static class RouterRouteResource extends AbstractResource {

        private final UUID routerId;

        @Inject
        public RouterRouteResource(RestApiConfig config,
                                   UriInfo uriInfo,
                                   SecurityContext context,
                                   Validator validator,
                                   DataClient dataClient,
                                   @Assisted UUID routerId) {
            super(config, uriInfo, context, dataClient, validator);
            this.routerId = routerId;
        }

        /**
         * Handler for creating a router route.
         *
         * @param route Route object.
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Route route)
                throws StateAccessException,
                SerializationException {

            route.routerId = routerId;

            validate(route);
            throwIfNextPortNotValid(route);

            authoriser.tryAuthoriseRouter(routerId,
                                          "add route to this router");

            UUID id = dataClient.routesCreate(RouteDataConverter.toData(route));
            routerEvent.routeCreate(routerId, dataClient.routesGet(id));
            return Response.created(ResourceUriBuilder.getRoute(getBaseUri(), id))
                           .build();
        }

        /**
         * Handler to list routes.
         *
         * @throws StateAccessException Data access error.
         * @return A list of Route objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Route> list() throws StateAccessException,
                                         SerializationException {

            authoriser.tryAuthoriseRouter(routerId, "view these routes");

            List<org.midonet.cluster.data.Route> routeDataList =
                    dataClient.routesFindByRouter(routerId);

            if (routeDataList == null) {
                return new ArrayList<>(0);
            }

            List<Route> routes = new ArrayList<>(routeDataList.size());
            for (org.midonet.cluster.data.Route routeData : routeDataList) {
                routes.add(RouteDataConverter.fromData(routeData, getBaseUri()));
            }
            return routes;
        }

        private void throwIfNextPortNotValid(Route route) {
            if (route.type != Route.NextHop.Normal) {
                // The validation only applies to 'normal' routes.
                return;
            }

            if (null == route.nextHopPort) {
                throw new BadRequestHttpException(getMessage(
                    MessageProperty.ROUTE_NEXT_HOP_PORT_NOT_NULL));
            }

            try {
                Port<?, ?> port = dataClient.portsGet(route.nextHopPort);
                if (null == port || !port.getDeviceId().equals(route.routerId)) {
                    throw new BadRequestHttpException(getMessage(
                        MessageProperty.ROUTE_NEXT_HOP_PORT_NOT_NULL));
                }
            } catch (StateAccessException e) {
                throw new BadRequestHttpException(getMessage(
                    MessageProperty.ROUTE_NEXT_HOP_PORT_NOT_NULL));
            } catch (SerializationException e) {
                throw new RuntimeException("Serialization exception occurred "
                                           + "in validation", e);
            }
        }
    }
}
