/*
 * @(#)RouterResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
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
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.RouterDataAccessor;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.rest_api.v1.resources.ChainResource.RouterChainResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.PortResource.RouterPortResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouteResource.RouterRouteResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual Router.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/routers")
public class RouterResource extends RestResource {
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
        return new RouterPortResource(zookeeperConn, id);
    }

    /**
     * Route resource locator for routers
     */
    @Path("/{id}/routes")
    public RouterRouteResource getRouteResource(@PathParam("id") UUID id) {
        return new RouterRouteResource(zookeeperConn, id);
    }

    /**
     * Chain resource locator for routers
     */
    @Path("/{id}/chains")
    public RouterChainResource getChainResource(@PathParam("id") UUID id) {
        return new RouterChainResource(zookeeperConn, id);
    }

    /**
     * Router resource locator for routers
     */
    @Path("/{id}/routers")
    public RouterRouterResource getRouterResource(@PathParam("id") UUID id) {
        return new RouterRouterResource(zookeeperConn, id);
    }

	/**
	 * Get the Router with the given ID.
	 * 
	 * @param id
	 *            Router UUID.
	 * @return Router object.
	 * @throws StateAccessException
	 * @throws Exception
	 */
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Router get(@PathParam("id") UUID id) throws StateAccessException {
        // Get a router for the given ID.
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public Response update(@PathParam("id") UUID id, Router router) throws StateAccessException {
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        try {
            dao.update(id, router);
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
    public void delete(@PathParam("id") UUID id) throws StateAccessException {
        RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public static class TenantRouterResource extends RestResource {

        private UUID tenantId = null;

        /**
         * Default constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param tenantId
         *            UUID of a tenant.
         */
        public TenantRouterResource(String zkConn, UUID tenantId) {
            this.zookeeperConn = zkConn;
            this.tenantId = tenantId;
        }

        /**
         * Return a list of routers.
         * 
         * @return A list of Router objects.
         * @throws StateAccessException 
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Router[] list() throws StateAccessException {
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public Response create(Router router, @Context UriInfo uriInfo) throws StateAccessException {
            router.setTenantId(tenantId);
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
    public static class RouterRouterResource extends RestResource {

        private UUID routerId = null;

        /**
         * Default constructor.
         * 
         * @param zkConn
         *            Zookeeper connection string.
         * @param routerId
         *            UUID of a router.
         */
        public RouterRouterResource(String zkConn, UUID routerId) {
            this.zookeeperConn = zkConn;
            this.routerId = routerId;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Response create(LogicalRouterPort port, @Context UriInfo uriInfo) throws StateAccessException {
            port.setDeviceId(routerId);
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);

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
            URI uri = uriInfo.getBaseUriBuilder().path(
                    "routers/" + peerRouter.getPeerRouterId()).build();
            return Response.created(uri).entity(peerRouter).build();
        }

        @GET
        @Path("{id}")
        @Produces(MediaType.APPLICATION_JSON)
		public PeerRouterLink get(@PathParam("id") UUID id)
				throws StateAccessException {
            RouterDataAccessor dao = new RouterDataAccessor(zookeeperConn,
                    zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
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
