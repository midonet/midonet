/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual Router.
 *
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterResource {
    /*
     * Implements REST API endpoints for routers.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouterResource.class);

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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RouterDao dao = daoFactory.getRouterDao();
        try {
            if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
                throw new UnauthorizedException(
                        "Not authorized to delete this router.");
            }
            dao.delete(id);
        } catch (NoStatePathException e) {
            log.error("A path was not found or was deleted twice", e);
            throw e;
        } catch (StateAccessException e) {
            log.error("StateAccessException error.", e);
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.", e);
            throw new UnknownRestApiException(e);
        }
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A Router object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Router get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws UnauthorizedException, StateAccessException {

        RouterDao dao = daoFactory.getRouterDao();
        Router router = null;
        try {
            if (!authorizer.routerAuthorized(context, AuthAction.READ, id)) {
                throw new UnauthorizedException(
                        "Not authorized to view this router.");
            }
            router = dao.get(id);
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
        router.setBaseUri(uriInfo.getBaseUri());
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
     * @returns RouterRouterResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTERS)
    public RouterLinkResource getLinkResource(@PathParam("id") UUID id) {
        return new RouterLinkResource(id);
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
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @PUT
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("id") UUID id, Router router,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RouterDao dao = daoFactory.getRouterDao();
        router.setId(id);
        try {
            if (!authorizer.routerAuthorized(context, AuthAction.WRITE, id)) {
                throw new UnauthorizedException(
                        "Not authorized to update this router.");
            }
            dao.update(router);
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

        return Response.ok().build();
    }
}
