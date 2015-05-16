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

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
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
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.AdRouteDataConverter;
import org.midonet.cluster.rest_api.models.AdRoute;
import org.midonet.event.topology.BgpEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.conversion.AdRouteDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.AdRouteDataConverter.toData;

/**
 * Root resource class for advertising routes.
 */
@RequestScoped
public class AdRouteResource extends AbstractResource {

    private final static BgpEvent bgpEvent = new BgpEvent();

    @Inject
    public AdRouteResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context,
                           DataClient dataClient) {
        super(config, uriInfo, context, dataClient, null);
    }

    /**
     * Handler to deleting an advertised route.
     *
     * @param id AdRoute ID from the request.
     * @throws StateAccessException Data access error.
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

        authoriser.tryAuthoriseAdRoute(id, "delete this ad route.");

        dataClient.adRoutesDelete(id);
        bgpEvent.routeDelete(adRouteData.getBgpId(), adRouteData);
    }

    /**
     * Handler to getting BGP advertised route.
     *
     * @param id Ad route ID from the request.
     * @throws StateAccessException Data access error.
     * @return An AdRoute object.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
    public AdRoute get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.AdRoute adRouteData =
            authoriser.tryAuthoriseAdRoute(id, "view this ad route.");

        if (adRouteData == null) {
            throw notFoundException(id, "ad route");
        }

        return AdRouteDataConverter.fromData(adRouteData, getBaseUri());
    }

    /**
     * Sub-resource class for bgp's advertising route.
     */
    @RequestScoped
    public static class BgpAdRouteResource extends AbstractResource {

        private final UUID bgpId;

        @Inject
        public BgpAdRouteResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context,
                                  DataClient dataClient,
                                  @Assisted UUID bgpId) {
            super(config, uriInfo, context, dataClient, null);
            this.bgpId = bgpId;
        }

        /**
         * Handler for creating BGP advertised route.
         *
         * @param adRoute AdRoute object.
         * @throws StateAccessException Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
                    MediaType.APPLICATION_JSON })
        public Response create(AdRoute adRoute) throws StateAccessException,
                                                       SerializationException {

            adRoute.bgpId = bgpId;

            authoriser.tryAuthoriseBgp(bgpId,
                                       "add ad route to this BGP.");

            UUID id;
            try {
                id = dataClient.adRoutesCreate(toData(adRoute));
            } catch (UnknownHostException e) {
                throw new BadRequestHttpException("Invalid nwPrefix: " +
                                                  adRoute.nwPrefix);
            }
            bgpEvent.routeCreate(bgpId, dataClient.adRoutesGet(id));
            return Response.created(ResourceUriBuilder.getAdRoute(getBaseUri(),
                                                                  id)).build();
        }

        /**
         * Handler to getting a list of BGP advertised routes.
         *
         * @throws StateAccessException Data access error.
         * @return A list of AdRoute objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON })
        public List<AdRoute> list() throws StateAccessException,
                                           SerializationException {

            authoriser.tryAuthoriseBgp(bgpId, "view these advertised routes.");

            List<org.midonet.cluster.data.AdRoute> adRouteDataList =
                    dataClient.adRoutesFindByBgp(bgpId);
            List<AdRoute> adRoutes = new ArrayList<>();
            if (adRouteDataList != null) {
                for (org.midonet.cluster.data.AdRoute adRouteData :
                        adRouteDataList) {
                    adRoutes.add(fromData(adRouteData, getBaseUri()));
                }
            }
            return adRoutes;
        }
    }
}
