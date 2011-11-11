/*
 * @(#)RuleResource        1.6 11/09/05
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
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for rules.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/rules")
public class RuleResource {

    private final static Logger log = LoggerFactory
            .getLogger(RuleResource.class);

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Rule get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context DaoFactory daoFactory) throws StateAccessException,
            UnauthorizedException {
        RuleDao dao = daoFactory.getRuleDao();
        if (!AuthManager.isOwner(context, dao, id)) {
            throw new UnauthorizedException("Can only see your own rule.");
        }

        try {
            return dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

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

        public ChainRuleResource(UUID chainId) {
            this.chainId = chainId;
        }

        private boolean isChainOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            OwnerQueryable q = daoFactory.getChainDao();
            return AuthManager.isOwner(context, q, chainId);
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public List<Rule> list(@Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
            RuleDao dao = daoFactory.getRuleDao();
            if (!isChainOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own rule.");
            }

            try {
                return dao.list(chainId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
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

            URI uri = uriInfo.getBaseUriBuilder().path("rules/" + id).build();
            return Response.created(uri).build();
        }
    }
}
