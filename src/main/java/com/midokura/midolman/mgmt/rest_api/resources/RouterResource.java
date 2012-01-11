/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

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
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.RouterChainResource;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource.RouterTableResource;
import com.midokura.midolman.mgmt.rest_api.resources.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.rest_api.resources.RouteResource.RouterRouteResource;
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
     * Port resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterPortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.PORTS)
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
    @Path("/{id}" + UriManager.ROUTES)
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(id);
    }

    /**
     * Chain resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterChainResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.CHAINS)
    public RouterChainResource getChainResource(@PathParam("id") UUID id) {
        return new RouterChainResource(id);
    }

    /**
     * Chain table resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterTableResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.TABLES)
    public RouterTableResource getTableResource(@PathParam("id") UUID id) {
        return new RouterTableResource(id);
    }

    /**
     * Router resource locator for routers.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterRouterResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.ROUTERS)
    public RouterRouterResource getRouterResource(@PathParam("id") UUID id) {
        return new RouterRouterResource(id);
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
            @Context DaoFactory daoFactory) throws UnauthorizedException,
            StateAccessException {
        RouterDao dao = daoFactory.getRouterDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
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
        router.setBaseUri(uriInfo.getBaseUri());
        return router;
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
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
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
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouterDao dao = daoFactory.getRouterDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only update your own router.");
        }

        router.setId(id);
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

    /**
     * Handler to deleting a router.
     *
     * @param id
     *            Router ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RouterDao dao = daoFactory.getRouterDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only update your own router.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
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
         * Constructor
         *
         * @param tenantId
         *            ID of a tenant.
         */
        public TenantRouterResource(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * Handler to list tenant routers.
         *
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A list of Router objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Router> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!AuthManager.isSelf(context, tenantId)) {
                throw new UnauthorizedException(
                        "Can only see your own routers.");
            }

            RouterDao dao = daoFactory.getRouterDao();
            List<Router> routers = null;
            try {
                routers = dao.list(tenantId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            for (UriResource resource : routers) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return routers;
        }

        /**
         * Handler for creating a tenant router.
         *
         * @param router
         *            Router object.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_ROUTER_JSON,
                MediaType.APPLICATION_JSON })
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

            return Response.created(
                    UriManager.getRouter(uriInfo.getBaseUri(), id)).build();
        }
    }

    /**
     * Sub-resource class for router's peer router.
     */
    public static class RouterRouterResource {

        private UUID routerId = null;

        /**
         * Constructor
         *
         * @param routerId
         *            ID of a router.
         */
        public RouterRouterResource(UUID routerId) {
            this.routerId = routerId;
        }

        /**
         * Handler for creating a router to router link.
         *
         * @param port
         *            LogicalRouterPort object.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @returns Response object with 201 status code set if successful. Body
         *          is set to PeerRouterLink.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(LogicalRouterPort port,
                @Context UriInfo uriInfo, @Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to link routers.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            port.setDeviceId(routerId);

            PeerRouterLink peerRouter = null;
            try {
                peerRouter = dao.create(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            peerRouter.setBaseUri(uriInfo.getBaseUri());
            return Response.created(peerRouter.getUri()).entity(peerRouter)
                    .build();
        }

        /**
         * Handler to deleting a router link.
         *
         * @param peerId
         *            Peer router ID from the request.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         */
        @DELETE
        @Path("{id}")
        public void delete(@PathParam("id") UUID peerId,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to delete router link.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            try {
                dao.delete(routerId, peerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        /**
         * Handler to getting a router to router link.
         *
         * @param id
         *            Peer router ID from the request.
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A PeerRouterLink object.
         */
        @GET
        @Path("{id}")
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public PeerRouterLink get(@PathParam("id") UUID id,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            if (!AuthManager.isOwner(context, (OwnerQueryable) dao, routerId)) {
                throw new UnauthorizedException(
                        "Must be a owner to see the linked routers.");
            }

            PeerRouterLink link = null;
            try {
                link = dao.get(routerId, id);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            link.setBaseUri(uriInfo.getBaseUri());
            return link;
        }

        /**
         * Handler to list router links.
         *
         * @param context
         *            Object that holds the security data.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param daoFactory
         *            Data access factory object.
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A list of PeerRouterLink objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PeerRouterLink> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!AuthManager.isServiceProvider(context)) {
                throw new UnauthorizedException(
                        "Must be a service provider to see the linked routers.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            List<PeerRouterLink> links = null;
            try {
                links = dao.list(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            for (UriResource resource : links) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return links;
        }

    }
}
