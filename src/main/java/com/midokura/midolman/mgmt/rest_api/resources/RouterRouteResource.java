/*
 * @(#)RouterRouteResource        1.6 12/1/11
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
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Sub-resource class for router's route.
 */
public class RouterRouteResource {

    private final static Logger log = LoggerFactory
            .getLogger(RouterRouteResource.class);
    private final UUID routerId;

    /**
     * Constructor your own route
     *
     * @param UUID
     *            routerId ID of a router.
     */
    public RouterRouteResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler for creating a router route.
     *
     * @param router
     *            Route object.
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
    @Consumes({ VendorMediaType.APPLICATION_ROUTE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Route route, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            ZkStateSerializationException, UnauthorizedException {

        RouteDao dao = daoFactory.getRouteDao();
        route.setRouterId(routerId);
        UUID id = null;
        try {
            if (!authorizer.routerAuthorized(context, AuthAction.WRITE,
                    routerId)) {
                throw new UnauthorizedException(
                        "Not authorized to add route to this router.");
            }
            id = dao.create(route);
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

        return Response.created(UriManager.getRoute(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler to list routes.
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
     * @return A list of Route objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Route> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RouteDao dao = daoFactory.getRouteDao();
        List<Route> routes = null;
        try {
            if (!authorizer
                    .routerAuthorized(context, AuthAction.READ, routerId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these routes.");
            }
            routes = dao.list(routerId);
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

        for (UriResource resource : routes) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return routes;
    }
}