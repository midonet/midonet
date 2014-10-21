/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.filter.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.packets.MAC;
import org.midonet.event.topology.RuleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.filter.Rule;
import org.midonet.api.filter.RuleFactory;
import org.midonet.api.filter.auth.ChainAuthorizer;
import org.midonet.api.filter.auth.RuleAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

/**
 * Root resource class for rules.
 */
@RequestScoped
public class RuleResource extends AbstractResource {

    private final static RuleEvent ruleEvent = new RuleEvent();

    private final RuleAuthorizer authorizer;

    @Inject
    public RuleResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context,
                        RuleAuthorizer authorizer, DataClient dataClient) {
        super(config, uriInfo, context, dataClient);
        this.authorizer = authorizer;
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
            throws StateAccessException,
            SerializationException {

        org.midonet.cluster.data.Rule<?, ?> ruleData = dataClient.rulesGet(id);
        if (ruleData == null) {
            return;
        }

        if (!authorizer.authorize(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this rule.");
        }

        dataClient.rulesDelete(id);
        ruleEvent.delete(id);
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
            VendorMediaType.APPLICATION_RULE_JSON_V2,
            MediaType.APPLICATION_JSON })
    public Rule get(@PathParam("id") UUID id) throws StateAccessException,
                SerializationException {

        if (!authorizer.authorize(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this rule.");
        }

        org.midonet.cluster.data.Rule<?, ?> ruleData = dataClient.rulesGet(id);
        if (ruleData == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        // Convert to the REST API DTO
        Rule rule = RuleFactory.createRule(ruleData);
        rule.setBaseUri(getBaseUri());

        return rule;
    }

    /**
     * Sub-resource class for chain's rules.
     */
    @RequestScoped
    public static class ChainRuleResource extends AbstractResource {

        private final UUID chainId;
        private final ChainAuthorizer authorizer;

        @Inject
        public ChainRuleResource(RestApiConfig config,
                                 UriInfo uriInfo,
                                 SecurityContext context,
                                 ChainAuthorizer authorizer,
                                 Validator validator,
                                 DataClient dataClient,
                                 @Assisted UUID chainId) {
            super(config, uriInfo, context, dataClient, validator);
            this.chainId = chainId;
            this.authorizer = authorizer;
        }

        /**
         * Handler for creating a chain rule.
         *
         * @param rule
         *            Rule object.
         * @throws StateAccessException
         *             Data access error.
         * @return Response object with 201 status code set if successful.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_RULE_JSON,
                VendorMediaType.APPLICATION_RULE_JSON_V2,
                MediaType.APPLICATION_JSON })
        public Response create(Rule rule)
                throws StateAccessException,
                SerializationException {

            rule.setChainId(chainId);
            if (rule.getPosition() == 0) {
                rule.setPosition(1);
            }

            validate(rule);

            if (!authorizer.authorize(context, AuthAction.WRITE, chainId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add rule to this chain.");
            }

            try {
                UUID id = dataClient.rulesCreate(rule.toData());
                ruleEvent.create(id, dataClient.rulesGet(id));
                return Response.created(
                        ResourceUriBuilder.getRule(getBaseUri(), id))
                        .build();
            } catch (RuleIndexOutOfBoundsException e) {
                throw new BadRequestHttpException(e, "Invalid rule position.");
            } catch (MAC.InvalidMacMaskException | MAC.InvalidMacException e) {
                throw new BadRequestHttpException(e, e.getMessage());
            }
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
                VendorMediaType.APPLICATION_RULE_COLLECTION_JSON_V2,
                MediaType.APPLICATION_JSON })
        public List<Rule> list() throws StateAccessException,
                SerializationException {

            if (!authorizer.authorize(context, AuthAction.READ, chainId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these rules.");
            }

            List<org.midonet.cluster.data.Rule<?,?>> ruleDataList;
            try {
                ruleDataList = dataClient.rulesFindByChain(chainId);
            } catch (NoStatePathException e) {
                throw new NotFoundHttpException(e, "No such chain" + chainId);
            }

            List<Rule> rules = new ArrayList<>();
            if (ruleDataList != null) {

                for (org.midonet.cluster.data.Rule<?, ?> ruleData :
                        ruleDataList) {
                    Rule rule = RuleFactory.createRule(ruleData);
                    rule.setBaseUri(getBaseUri());
                    rules.add(rule);
                }

            }
            return rules;
        }
    }
}
