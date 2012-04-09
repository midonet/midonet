/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual Router.
 */
public class RouterResource {
    /*
     * Implements REST API endpoints for routers.
     */

    /**
     * Handler to deleting a router.
     *
     * @param id
     *            Router ID from the request.
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
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this router.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        dao.delete(id);
    }

    /**
     * Handler to getting a router.
     *
     * @param id
     *            Router ID from the request.
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
     * @return A Router object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router.");
        }

        RouterDao dao = daoFactory.getRouterDao();
        Router router = dao.get(id);
        if (router != null) {
            router.setBaseUri(uriInfo.getBaseUri());
        }
        return router;
    }

    /**
     * Chain resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterChainResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.CHAINS)
    public RouterChainResource getChainResource(@PathParam("id") UUID id) {
        return new RouterChainResource(id);
    }

    /**
     * Router resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterLinkResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTERS)
    public RouterLinkResource getLinkResource(@PathParam("id") UUID id) {
        return new RouterLinkResource(id);
    }

    /**
     * Router resource locator for linked bridges.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterBridgesResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BRIDGES)
    public RouterBridgesResource getBridgesResource(@PathParam("id") UUID id) {
        return new RouterBridgesResource(id);
    }

    /**
     * Port resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return new RouterPortResource(id);
    }

    /**
     * Route resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterRouteResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTES)
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(id);
    }

    /**
     * Chain table resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterTableResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.TABLES)
    public RouterTableResource getTableResource(@PathParam("id") UUID id) {
        return new RouterTableResource(id);
    }

    /**
     * Handler to updating a router.
     *
     * @param id
     *            Router ID from the request.
     * @param router
     *            Router object.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("id") UUID id, Router router,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this router.");
        }
        RouterDao dao = daoFactory.getRouterDao();
        router.setId(id);
        dao.update(router);
        return Response.ok().build();
    }
}
