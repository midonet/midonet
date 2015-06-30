/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.api.neutron;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.SecurityGroupRule;
import org.midonet.cluster.services.rest_api.neutron.plugin.SecurityGroupApi;
import org.midonet.event.neutron.SecurityGroupRuleEvent;

public class SecurityGroupRuleResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        SecurityGroupRuleResource.class);
    private final static SecurityGroupRuleEvent SECURITY_GROUP_RULE_EVENT =
            new SecurityGroupRuleEvent();

    private final SecurityGroupApi api;

    @Inject
    public SecurityGroupRuleResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     SecurityGroupApi api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(SecurityGroupRule rule) {
        log.info("SecurityGroupRuleResource.create entered {}", rule);

        SecurityGroupRule r = api.createSecurityGroupRule(rule);
        SECURITY_GROUP_RULE_EVENT.create(r.id, r);
        log.info("SecurityGroupRuleResource.get exiting {}", r);
        return Response.created(
            NeutronUriBuilder.getSecurityGroupRule(
                getBaseUri(), r.id)).entity(r).build();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<SecurityGroupRule> rules) {
        log.info("SecurityGroupRuleResource.createBulk entered");

        List<SecurityGroupRule> outRules =
            api.createSecurityGroupRuleBulk(rules);
        for (SecurityGroupRule r : outRules) {
            SECURITY_GROUP_RULE_EVENT.create(r.id, r);
        }
        return Response.created(NeutronUriBuilder.getSecurityGroupRules(
            getBaseUri())).entity(outRules).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("SecurityGroupRuleResource.delete entered {}", id);
        api.deleteSecurityGroupRule(id);
        SECURITY_GROUP_RULE_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public SecurityGroupRule get(@PathParam("id") UUID id) {
        log.info("SecurityGroupRuleResource.get entered {}", id);

        SecurityGroupRule rule = api.getSecurityGroupRule(id);
        if (rule == null) {
            throw notFoundException(id, "security group rule");
        }

        log.info("SecurityGroupRuleResource.get exiting {}", rule);
        return rule;
    }

    @GET
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<SecurityGroupRule> list() {
        log.info("SecurityGroupRuleResource.list entered");
        return api.getSecurityGroupRules();
    }
}
