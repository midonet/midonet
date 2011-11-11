/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.RouterChainResource;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.RouterTableResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.RouteResource.RouterRouteResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual Router.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/routers")
public class RouterResource {
    /*
     * Implements REST API endpoints for routers.
     */

    private final static Logger log = LoggerFactory
            .getLogger(RouterResource.class);

    /**
     * Port resource locator for routers
     */
    @Path("/{id}/ports")
    public RouterPortResource getPortResource(@PathParam("id") UUID id) {
        return new RouterPortResource(id);
    }

    /**
     * Route resource locator for routers
     */
    @Path("/{id}/routes")
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(id);
    }

    /**
     * Chain resource locator for routers
     */
    @Path("/{id}/chains")
    public RouterChainResource getChainResource(@PathParam("id") UUID id) {
        return new RouterChainResource(id);
    }

    /**
     * Chain table resource locator for routers
     */
    @Path("/{id}/tables")
    public RouterTableResource getTableResource(@PathParam("id") UUID id) {
        return new RouterTableResource(id);
    }

    /**
     * Router resource locator for routers
     */
    @Path("/{id}/routers")
    public RouterRouterResource getRouterResource(@PathParam("id") UUID id) {
        return new RouterRouterResource(id);
    }

    /**
     * Get the Router with the given ID.
     * 
     * @param id
     *            Router UUID.
     * @return Router object.
     * @throws Exception
     * @throws UnauthorizedException
     * @throws StateAccessException
     * @throws Exception
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Router get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws UnauthorizedException, StateAccessException {
        // Get a router for the given ID.
        RouterDao dao = daoFactory.getRouterDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own router.");
        }

        Router router = null;
        try {
            router = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return router;
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") UUID id, Router router,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouterDao dao = daoFactory.getRouterDao();
        router.setId(id);

        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only update your own router.");
        }

        try {
            dao.update(router);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }

        return Response.ok().build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouterDao dao = daoFactory.getRouterDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only update your own router.");
        }

        try {
            dao.delete(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Sub-resource class for tenant's virtual router.
     */
    public static class TenantRouterResource {

        private String tenantId = null;

        /**
         * Default constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param tenantId
         *            UUID of a tenant.
         */
        public TenantRouterResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Return a list of routers.
         * 
         * @return A list of Router objects.
         * @throws StateAccessException
         * @throws UnauthorizedException
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Router> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only see your own routers.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            try {
                return dao.list(tenantId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        /**
         * Handler for create router API call.
         * 
         * @param router
         *            Router object mapped to the request input.
         * @throws StateAccessException
         * @throws UnauthorizedException
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Router router, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only create your own router.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            router.setTenantId(tenantId);
            UUID id = null;
            try {
                id = dao.create(router);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            URI uri = uriInfo.getBaseUriBuilder().path("routers/" + id).build();
            return Response.created(uri).build();
        }
    }

    /**
     * Sub-resource class for router's peer router.
     */
    public static class RouterRouterResource {

        private UUID routerId = null;

        /**
         * Default constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param routerId
         *            UUID of a router.
         */
        public RouterRouterResource(UUID routerId) {
            this.routerId = routerId;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Response create(LogicalRouterPort port,
                @Context UriInfo uriInfo, @Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to link routers.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            port.setDeviceId(routerId);

            PeerRouterLink peerRouter = null;
            try {
                peerRouter = dao.createLink(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            URI uri = uriInfo.getBaseUriBuilder()
                    .path("routers/" + port.getPeerRouterId()).build();
            return Response.created(uri).entity(peerRouter).build();
        }

        @DELETE
        @Path("{id}")
        public void delete(@PathParam("id") UUID peerId,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to delete router link.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            try {
                dao.deleteLink(routerId, peerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        @GET
        @Path("{id}")
        @Produces(MediaType.APPLICATION_JSON)
        public PeerRouterLink get(@PathParam("id") UUID id,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to see the linked routers.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            PeerRouterLink link = null;
            try {
                link = dao.getPeerRouterLink(routerId, id);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            return link;
        }
    }
}
