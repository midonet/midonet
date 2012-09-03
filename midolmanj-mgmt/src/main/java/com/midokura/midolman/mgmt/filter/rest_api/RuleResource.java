/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.filter.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.filter.Rule;
import com.midokura.midolman.mgmt.filter.RuleFactory;
import com.midokura.midolman.mgmt.filter.auth.ChainAuthorizer;
import com.midokura.midolman.mgmt.filter.auth.RuleAuthorizer;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Root resource class for rules.
 */
@RequestScoped
public class RuleResource {

    private final static Logger log = LoggerFactory
            .getLogger(RuleResource.class);

    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final Authorizer authorizer;
    private final DataClient dataClient;

    @Inject
    public RuleResource(UriInfo uriInfo, SecurityContext context,
                        RuleAuthorizer authorizer, DataClient dataClient) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.authorizer = authorizer;
        this.dataClient = dataClient;
    }

    /**
     * Handler to deleting a rule.
     *
     * @param id
     *            Rule ID from the request.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        com.midokura.midonet.cluster.data.Rule ruleData = dataClient.rulesGet(
                id);
        if (ruleData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this rule.");
        }

        dataClient.rulesDelete(id);
    }

    /**
     * Handler to getting a rule.
     *
     * @param id
     *            Rule ID from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Rule object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_RULE_JSON,
            MediaType.APPLICATION_JSON })
    public Rule get(@PathParam("id") UUID id) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this rule.");
        }

        com.midokura.midonet.cluster.data.Rule ruleData = dataClient.rulesGet(
                id);
        if (ruleData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Rule rule = RuleFactory.createRule(ruleData);
        rule.setBaseUri(uriInfo.getBaseUri());

        return rule;
    }

    /**
     * Sub-resource class for chain's rules.
     */
    @RequestScoped
    public static class ChainRuleResource {

        private final UUID chainId;
        private final SecurityContext context;
        private final UriInfo uriInfo;
        private final Authorizer authorizer;
        private final Validator validator;
        private final DataClient dataClient;

        @Inject
        public ChainRuleResource(UriInfo uriInfo,
                                 SecurityContext context,
                                 ChainAuthorizer authorizer,
                                 Validator validator,
                                 DataClient dataClient,
                                 @Assisted UUID chainId) {
            this.chainId = chainId;
            this.context = context;
            this.uriInfo = uriInfo;
            this.authorizer = authorizer;
            this.validator = validator;
            this.dataClient = dataClient;
        }

        /**
         * Handler for creating a chain rule.
         *
         * @param rule
         *            Rule object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_RULE_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Rule rule)
                throws StateAccessException, InvalidStateOperationException {

            rule.setChainId(chainId);
            if (rule.getPosition() == 0) {
                rule.setPosition(1);
            }

            Set<ConstraintViolation<Rule>> violations = validator
                    .validate(rule);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }

            if (!authorizer.authorize(context, AuthAction.WRITE, chainId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add rule to this chain.");
            }

            UUID id = null;
            try {
                id = dataClient.rulesCreate(rule.toData());
            } catch (RuleIndexOutOfBoundsException e) {
                throw new BadRequestHttpException("Invalid rule position.");
            }
            return Response.created(
                    ResourceUriBuilder.getRule(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list chain rules.
         *
         * @throws StateAccessException
         *             Data access error.
         * @return A list of Rule objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_RULE_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Rule> list() throws StateAccessException {

            if (!authorizer.authorize(context, AuthAction.READ, chainId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these rules.");
            }

            List<com.midokura.midonet.cluster.data.Rule<?,?>> ruleDataList =
                    dataClient.rulesFindByChain(chainId);
            List<Rule> rules = new ArrayList<Rule>();
            if (ruleDataList != null) {

                for (com.midokura.midonet.cluster.data.Rule ruleData :
                        ruleDataList) {
                    Rule rule = RuleFactory.createRule(ruleData);
                    rule.setBaseUri(uriInfo.getBaseUri());
                    rules.add(rule);
                }

            }
            return rules;
        }
    }
}
