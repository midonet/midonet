/*
 * @(#)RuleResource        1.6 11/09/05
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
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for rules.
 *
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleResource {

    private final static Logger log = LoggerFactory
            .getLogger(RuleResource.class);

    /**
     * Handler to getting a rule.
     *
     * @param id
     *            Rule ID from the request.
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
     * @return A Rule object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_RULE_JSON,
            MediaType.APPLICATION_JSON })
    public Rule get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        RuleDao dao = daoFactory.getRuleDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own rule.");
        }

        Rule rule = null;
        try {
            rule = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        rule.setBaseUri(uriInfo.getBaseUri());
        return rule;
    }

    /**
     * Handler to deleting a rule.
     *
     * @param id
     *            Rule ID from the request.
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
        RuleDao dao = daoFactory.getRuleDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only delete your own rule.");
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
     * Sub-resource class for chain's rules.
     */
    public static class ChainRuleResource {

        private UUID chainId = null;

        /**
         * Constructor
         *
         * @param chainId
         *            ID of a chain.
         */
        public ChainRuleResource(UUID chainId) {
            this.chainId = chainId;
        }

        private boolean isChainOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getChainDao();
            return AuthManager.isOwner(context, q, chainId);
        }

        /**
         * Handler to list chain rules.
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
         * @return A list of Rule objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_RULE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Rule> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            RuleDao dao = daoFactory.getRuleDao();
            if (!isChainOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own rule.");
            }

            List<Rule> rules = null;
            try {
                rules = dao.list(chainId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            for (UriResource resource : rules) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return rules;
        }

        /**
         * Handler for creating a chain rule.
         *
         * @param router
         *            Rule object.
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
        @Consumes({ VendorMediaType.APPLICATION_RULE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Rule rule, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, RuleIndexOutOfBoundsException,
                UnauthorizedException {
            if (!isChainOwner(context, daoFactory)) {
                throw new UnauthorizedException(
                        "Can only create your own rule.");
            }

            RuleDao dao = daoFactory.getRuleDao();
            rule.setChainId(chainId);
            UUID id = null;
            try {
                id = dao.create(rule);
            } catch (RuleIndexOutOfBoundsException e) {
                log.error("Invalid rule position.", e);
                throw e;
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response.created(
                    UriManager.getRule(uriInfo.getBaseUri(), id)).build();
        }
    }
}
