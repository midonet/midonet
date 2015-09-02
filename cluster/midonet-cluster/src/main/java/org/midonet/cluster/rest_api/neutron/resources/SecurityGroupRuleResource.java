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

package org.midonet.cluster.rest_api.neutron.resources;

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
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.SecurityGroupRule;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.services.rest_api.neutron.plugin.SecurityGroupApi;

import static org.midonet.cluster.rest_api.validation.MessageProperty.*;

public class SecurityGroupRuleResource {

    private final SecurityGroupApi api;
    private final URI baseUri;

    @Inject
    public SecurityGroupRuleResource(UriInfo uriInfo, SecurityGroupApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    public Response create(SecurityGroupRule rule) {
        SecurityGroupRule r = api.createSecurityGroupRule(rule);
        return Response.created(NeutronUriBuilder.getSecurityGroupRule(
                baseUri, r.id)).entity(r).build();
    }

    @POST
    @Consumes(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    public Response createBulk(List<SecurityGroupRule> rules) {
        List<SecurityGroupRule> outRules = api.createSecurityGroupRuleBulk
            (rules);
        return Response.created(NeutronUriBuilder.getSecurityGroupRules(
            baseUri)).entity(outRules).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteSecurityGroupRule(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1)
    public SecurityGroupRule get(@PathParam("id") UUID id) {
        SecurityGroupRule rule = api.getSecurityGroupRule(id);
        if (rule == null) {
            throw new NotFoundHttpException(
                getMessage(RESOURCE_NOT_FOUND, "SecurityGroupRule", id));
        }
        return rule;
    }

    @GET
    @Produces(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1)
    public List<SecurityGroupRule> list() {
        return api.getSecurityGroupRules();
    }
}
