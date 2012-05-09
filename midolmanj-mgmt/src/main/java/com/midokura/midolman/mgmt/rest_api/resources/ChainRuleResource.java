/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for chain's rules.
 */
public class ChainRuleResource {

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
     * @param rule
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
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_RULE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Rule rule, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.chainAuthorized(context, AuthAction.WRITE, chainId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add port to this chain.");
        }

        RuleDao dao = daoFactory.getRuleDao();

        rule.setChainId(chainId);
        UUID jumpChainID = null;
        if (rule.getJumpChainName() != null) {
            ChainDao chainDao = daoFactory.getChainDao();
            Chain chain = chainDao.get(chainId);
            Chain jumpChain =
                    chainDao.get(chain.getTenantId(), rule.getJumpChainName());
            jumpChainID = jumpChain.getId();
        }
        UUID id = null;
        try {
            id = dao.create(rule, jumpChainID);
        } catch (RuleIndexOutOfBoundsException e) {
            throw new BadRequestHttpException("Invalid rule position.");
        }
        return Response.created(
                ResourceUriBuilder.getRule(uriInfo.getBaseUri(), id)).build();
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
     * @return A list of Rule objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_RULE_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Rule> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.chainAuthorized(context, AuthAction.READ, chainId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these rules.");
        }

        RuleDao dao = daoFactory.getRuleDao();
        List<Rule> rules = dao.list(chainId);
        if (rules != null) {
            for (UriResource resource : rules) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return rules;
    }
}
