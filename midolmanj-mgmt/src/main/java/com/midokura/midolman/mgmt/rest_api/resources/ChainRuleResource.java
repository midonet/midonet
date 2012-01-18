/*
 * @(#)ChainRuleResource        1.6 12/1/11
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
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for chain's rules.
 */
public class ChainRuleResource {

    private final static Logger log = LoggerFactory
            .getLogger(ChainRuleResource.class);
    private final UUID chainId;

    /**
     * Constructor
     *
     * @param chainId
     *            ID of a chain.
     */
    public ChainRuleResource(UUID chainId) {
        this.chainId = chainId;
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
     * @param authorizer
     *            Authorizer object.
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
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            RuleIndexOutOfBoundsException, UnauthorizedException {

        RuleDao dao = daoFactory.getRuleDao();
        rule.setChainId(chainId);
        UUID id = null;
        try {
            if (!authorizer.chainAuthorized(context, AuthAction.WRITE, chainId)) {
                throw new UnauthorizedException(
                        "Not authorized to add port to this chain.");
            }
            id = dao.create(rule);
        } catch (RuleIndexOutOfBoundsException e) {
            log.error("Invalid rule position.", e);
            throw e;
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

        return Response.created(ResourceUriBuilder.getRule(uriInfo.getBaseUri(), id))
                .build();
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
     * @param authorizer
     *            Authorizer object.
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
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RuleDao dao = daoFactory.getRuleDao();
        List<Rule> rules = null;
        try {
            if (!authorizer.chainAuthorized(context, AuthAction.READ, chainId)) {
                throw new UnauthorizedException(
                        "Not authorized to view these rules.");
            }
            rules = dao.list(chainId);
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

        for (UriResource resource : rules) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return rules;
    }
}
