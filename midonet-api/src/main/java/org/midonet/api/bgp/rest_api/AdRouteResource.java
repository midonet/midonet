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
package org.midonet.api.bgp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.bgp.AdRoute;
import org.midonet.api.bgp.auth.AdRouteAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.bgp.auth.BgpAuthorizer;
import org.midonet.event.topology.BgpEvent;
import org.midonet.util.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for advertising routes.
 */
@RequestScoped
public class AdRouteResource extends AbstractResource {

    private final static BgpEvent bgpEvent = new BgpEvent();

    private final AdRouteAuthorizer authorizer;

    @Inject
    public AdRouteResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context,
                           AdRouteAuthorizer authorizer,
                           DataClient dataClient) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
    }

    /**
     * Handler to deleting an advertised route.
     *
     * @param id
     *            AdRoute ID from the request.
    * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.AdRoute adRouteData =
                dataClient.adRoutesGet(id);
        if (adRouteData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this ad route.");
        }

        dataClient.adRoutesDelete(id);
        bgpEvent.routeDelete(adRouteData.getBgpId(), adRouteData);
    }

    /**
     * Handler to getting BGP advertised route.
     *
     * @param id
     *            Ad route ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return An AdRoute object.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public AdRoute get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this ad route.");
        }

        org.midonet.cluster.data.AdRoute adRouteData =
                dataClient.adRoutesGet(id);
        if (adRouteData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        AdRoute adRoute = new AdRoute(adRouteData);
        adRoute.setBaseUri(getBaseUri());

        return adRoute;
    }

    /**
     * Sub-resource class for bgp's advertising route.
     */
    @RequestScoped
    public static class BgpAdRouteResource extends AbstractResource {

        private final UUID bgpId;
        private final BgpAuthorizer authorizer;

        @Inject
        public BgpAdRouteResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context,
                                  BgpAuthorizer authorizer,
                                  DataClient dataClient,
                                  @Assisted UUID bgpId) {
            super(config, uriInfo, context, dataClient);
            this.bgpId = bgpId;
            this.authorizer = authorizer;
        }

        /**
         * Handler for creating BGP advertised route.
         *
         * @param adRoute
         *            AdRoute object.
         * @throws StateAccessException
         *             Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(AdRoute adRoute)
                throws StateAccessException,
                SerializationException {

            adRoute.setBgpId(bgpId);

            if (!authorizer.authorize(context, AuthAction.WRITE, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add ad route to this BGP.");
            }

            UUID id = dataClient.adRoutesCreate(adRoute.toData());
            bgpEvent.routeCreate(bgpId, dataClient.adRoutesGet(id));
            return Response.created(
                    ResourceUriBuilder.getAdRoute(getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to getting a list of BGP advertised routes.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of AdRoute objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<AdRoute> list() throws StateAccessException,
                SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these advertised routes.");
            }

            List<org.midonet.cluster.data.AdRoute> adRouteDataList =
                    dataClient.adRoutesFindByBgp(bgpId);
            List<AdRoute> adRoutes = new ArrayList<AdRoute>();
            if (adRouteDataList != null) {
                for (org.midonet.cluster.data.AdRoute adRouteData :
                        adRouteDataList) {
                    AdRoute adRoute = new AdRoute(adRouteData);
                    adRoute.setBaseUri(getBaseUri());
                    adRoutes.add(adRoute);
                }

            }
            return adRoutes;
        }
    }
}
