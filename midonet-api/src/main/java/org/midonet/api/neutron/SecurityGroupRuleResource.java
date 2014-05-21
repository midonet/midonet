/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.SecurityGroupApi;
import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.*;

public class SecurityGroupRuleResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            SecurityGroupRuleResource.class);

    private final SecurityGroupApi api;

    @Inject
    public SecurityGroupRuleResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     SecurityGroupApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(SecurityGroupRule rule)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("SecurityGroupRuleResource.create entered {}", rule);

        try {

            SecurityGroupRule r = api.createSecurityGroupRule(rule);

            log.info("SecurityGroupRuleResource.get exiting {}", r);
            return Response.created(
                    NeutronUriBuilder.getSecurityGroupRule(
                            getBaseUri(), r.id)).entity(r).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<SecurityGroupRule> rules)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {
        log.info("SecurityGroupRuleResource.createBulk entered");

        try {
            List<SecurityGroupRule> outRules =
                    api.createSecurityGroupRuleBulk(rules);

            return Response.created(NeutronUriBuilder.getSecurityGroupRules(
                    getBaseUri())).entity(outRules).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupRuleResource.delete entered {}", id);
        api.deleteSecurityGroupRule(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public SecurityGroupRule get(@PathParam("id") UUID id)
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupRuleResource.get entered {}", id);

        SecurityGroupRule rule = api.getSecurityGroupRule(id);
        if (rule == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("SecurityGroupRuleResource.get exiting {}", rule);
        return rule;
    }

    @GET
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<SecurityGroupRule> list()
            throws SerializationException, StateAccessException {
        log.info("SecurityGroupRuleResource.list entered");
        return api.getSecurityGroupRules();
    }
}
