/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

/**
 * Root resource class for advertising routes.
 */
@RequestScoped
public class AdRouteResource {

    private final static Logger log = LoggerFactory
            .getLogger(AdRouteResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final AdRouteDao dao;

    @Inject
    public AdRouteResource(UriInfo uriInfo, SecurityContext context,
                           Authorizer authorizer, AdRouteDao dao) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dao = dao;
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
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.adRouteAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this advertised route.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist.");
        }
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
            throws StateAccessException {

        if (!authorizer.adRouteAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this advertised route.");
        }

        AdRoute adRoute = dao.get(id);
        if (adRoute == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        adRoute.setBaseUri(uriInfo.getBaseUri());

        return adRoute;
    }

    /**
     * Sub-resource class for bgp's advertising route.
     */
    @RequestScoped
    public static class BgpAdRouteResource {

        private final UUID bgpId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final AdRouteDao dao;

        @Inject
        public BgpAdRouteResource(UriInfo uriInfo,
                                  SecurityContext context,
                                  Authorizer authorizer,
                                  AdRouteDao dao,
                                  @Assisted UUID bgpId) {
            this.bgpId = bgpId;
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.dao = dao;
        }

        /**
         * Handler for creating BGP advertised route.
         *
         * @param adRoute
         *            AdRoute object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(AdRoute adRoute)
                throws StateAccessException, InvalidStateOperationException {

            if (!authorizer.bgpAuthorized(context, AuthAction.WRITE, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add ad route to this BGP.");
            }

            adRoute.setBgpId(bgpId);
            UUID id = dao.create(adRoute);
            return Response.created(
                    ResourceUriBuilder.getAdRoute(uriInfo.getBaseUri(), id))
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
        public List<AdRoute> list() throws StateAccessException {

            if (!authorizer.bgpAuthorized(context, AuthAction.READ, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these advertised routes.");
            }

            List<AdRoute> adRoutes = dao.findByBgp(bgpId);
            if (adRoutes != null) {
                for (UriResource resource : adRoutes) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return adRoutes;
        }
    }
}
