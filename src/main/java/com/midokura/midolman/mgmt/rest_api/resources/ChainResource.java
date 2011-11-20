/*
 * @(#)ChainResource        1.6 11/09/05
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
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.resources.RuleResource.ChainRuleResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for chains.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class ChainResource {

    private final static Logger log = LoggerFactory
            .getLogger(ChainResource.class);

    /**
     * Rule resource locator for chains.
     * 
     * @param id
     *            Chain ID from the request.
     * @returns ChainRuleResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.RULES)
    public ChainRuleResource getRuleResource(@PathParam("id") UUID id) {
        return new ChainRuleResource(id);
    }

    /**
     * Handler to getting a chain.
     * 
     * @param id
     *            Chain ID from the request.
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
     * @return A Chain object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
            MediaType.APPLICATION_JSON })
    public Chain get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        ChainDao dao = daoFactory.getChainDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own chain.");
        }

        Chain chain = null;
        try {
            chain = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        chain.setBaseUri(uriInfo.getBaseUri());
        return chain;
    }

    /**
     * Handler to deleting a chain.
     * 
     * @param id
     *            Chain ID from the request.
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
        ChainDao dao = daoFactory.getChainDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException(
                    "Can only delete your own advertised route.");
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
     * Sub-resource class for router's chain tables.
     */
    public static class RouterTableResource {

        private UUID routerId = null;

        /**
         * Constructor
         * 
         * @param routerId
         *            ID of a router.
         */
        public RouterTableResource(UUID routerId) {
            this.routerId = routerId;
        }

        /**
         * Chain resource locator for router.
         * 
         * @param id
         *            Chain ID from the request.
         * @returns RouterTableChainResource object to handle sub-resource
         *          requests.
         */
        @Path("/{name}" + UriManager.CHAINS)
        public RouterTableChainResource getChainTableResource(
                @PathParam("name") String name) {
            return new RouterTableChainResource(routerId, name);
        }
    }

    /**
     * Sub-resource class for router's table chains.
     */
    public static class RouterTableChainResource {

        private UUID routerId = null;
        private String table = null;

        /**
         * Constructor
         * 
         * @param routerId
         *            ID of a router.
         * @param table
         *            Chain table name.
         */
        public RouterTableChainResource(UUID routerId, String table) {
            this.routerId = routerId;
            this.table = table;
        }

        private boolean isRouterOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getRouterDao();
            return AuthManager.isOwner(context, q, routerId);
        }

        /**
         * Handler to list chains.
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
         * @return A list of Chain objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Chain> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own chains.");
            }

            ChainDao dao = daoFactory.getChainDao();
            List<Chain> chains = null;
            try {
                chains = dao.listTableChains(routerId, table);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            for (UriResource resource : chains) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return chains;
        }

        /**
         * Handler to getting a chain.
         * 
         * @param name
         *            Chain name from the request.
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
         * @return A Chain object.
         */
        @GET
        @Path("{name}")
        @Produces({ VendorMediaType.APPLICATION_CHAIN_JSON,
                MediaType.APPLICATION_JSON })
        public Chain get(@PathParam("name") String name,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own chains.");
            }

            ChainDao dao = daoFactory.getChainDao();
            Chain chain = null;
            try {
                chain = dao.get(routerId, table, name);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            chain.setBaseUri(uriInfo.getBaseUri());
            return chain;
        }
    }

    /**
     * Sub-resource class for router's table chains.
     */
    public static class RouterChainResource {

        private UUID routerId = null;

        /**
         * Constructor
         * 
         * @param routerId
         *            ID of a router.
         */
        public RouterChainResource(UUID routerId) {
            this.routerId = routerId;
        }

        private boolean isRouterOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getRouterDao();
            return AuthManager.isOwner(context, q, routerId);
        }

        /**
         * Handler for creating a router chain.
         * 
         * @param chain
         *            Chain object.
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
        @Consumes({ VendorMediaType.APPLICATION_CHAIN_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Chain chain, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own chains.");
            }

            ChainDao dao = daoFactory.getChainDao();
            chain.setRouterId(routerId);

            UUID id = null;
            try {
                id = dao.create(chain);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response.created(
                    UriManager.getChain(uriInfo.getBaseUri(), id)).build();
        }

        /**
         * Handler to getting a chain.
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
         * @return A list of Chain objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Chain> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own chains.");
            }

            ChainDao dao = daoFactory.getChainDao();
            List<Chain> chains = null;
            try {
                chains = dao.list(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            for (UriResource resource : chains) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return chains;
        }
    }
}
