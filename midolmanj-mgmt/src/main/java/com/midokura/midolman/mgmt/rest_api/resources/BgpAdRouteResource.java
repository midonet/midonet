/*
 * @(#)BgpAdRouteResource        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for bgp's advertising route.
 */
public class BgpAdRouteResource {

    private final UUID bgpId;
    private final static Logger log = LoggerFactory
            .getLogger(BgpAdRouteResource.class);

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
     * @param chain
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_AD_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(AdRoute adRoute, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        AdRouteDao dao = daoFactory.getAdRouteDao();
        adRoute.setBgpId(bgpId);
        UUID id = null;
        try {
            if (!authorizer.bgpAuthorized(context, AuthAction.WRITE, bgpId)) {
                throw new UnauthorizedException(
                        "Not authorized to add ad route to this BGP.");
            }
            id = dao.create(adRoute);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        return Response
                .created(ResourceUriBuilder.getAdRoute(uriInfo.getBaseUri(), id))
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of AdRoute objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_AD_ROUTE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<AdRoute> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        AdRouteDao dao = daoFactory.getAdRouteDao();
        List<AdRoute> adRoutes = null;
        try {
            if (!authorizer.bgpAuthorized(context, AuthAction.READ, bgpId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these advertised routes.");
            }
            adRoutes = dao.list(bgpId);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        for (UriResource resource : adRoutes) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return adRoutes;
    }
}
