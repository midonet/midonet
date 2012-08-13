/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.state.InvalidStateOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for advertising routes.
 */
public class AdRouteResource {
    /*
     * Implements REST API end points for ad_routes.
     */

    private final static Logger log = LoggerFactory
            .getLogger(AdRouteResource.class);

    /**
     * Handler to deleting an advertised route.
     *
     * @param id
     *            AdRoute ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer)
            throws StateAccessException, InvalidStateOperationException {

        if (!authorizer.adRouteAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this advertised route.");
        }

        AdRouteDao dao = daoFactory.getAdRouteDao();
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
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return An AdRoute object.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public AdRoute get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.adRouteAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this advertised route.");
        }

        AdRouteDao dao = daoFactory.getAdRouteDao();
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
    public static class BgpAdRouteResource {

        private final UUID bgpId;

        /**
         * Constructor
         *
         * @param bgpId
         *            ID of a BGP configuration record.
         */
        public BgpAdRouteResource(UUID bgpId) {
            this.bgpId = bgpId;
        }

        /**
         * Handler for creating BGP advertised route.
         *
         * @param adRoute
         *            AdRoute object.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(AdRoute adRoute, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer)
                throws StateAccessException, InvalidStateOperationException {

            if (!authorizer.bgpAuthorized(context, AuthAction.WRITE, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add ad route to this BGP.");
            }

            AdRouteDao dao = daoFactory.getAdRouteDao();
            adRoute.setBgpId(bgpId);
            UUID id = dao.create(adRoute);
            return Response.created(
                    ResourceUriBuilder.getAdRoute(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to getting a list of BGP advertised routes.
         *
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @return A list of AdRoute objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<AdRoute> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer.bgpAuthorized(context, AuthAction.READ, bgpId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these advertised routes.");
            }

            AdRouteDao dao = daoFactory.getAdRouteDao();
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
