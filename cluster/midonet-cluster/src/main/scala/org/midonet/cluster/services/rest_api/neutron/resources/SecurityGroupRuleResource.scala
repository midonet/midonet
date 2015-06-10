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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.util
import java.util.UUID
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs.{Consumes, DELETE, GET, POST, Path, PathParam, Produces}

import com.google.inject.Inject
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{SECURITY_GROUP_RULES, getUri}
import org.midonet.cluster.rest_api.neutron.models.SecurityGroupRule
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class SecurityGroupRuleResource @Inject() (uriInfo: UriInfo,
                                           api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1))
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1))
    def create(rule: SecurityGroupRule): Response = {
        val r =  api.createSecurityGroupRule(rule)
        Response.created(getUri(uriInfo.getBaseUri, SECURITY_GROUP_RULES, r.id))
                .entity(r).build
    }

    @POST
    @Consumes(Array(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1))
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1))
    def createBulk(rules: util.List[SecurityGroupRule]): Response = {
        val outRules = api.createSecurityGroupRuleBulk(rules)
        Response.created(getUri(uriInfo.getBaseUri, SECURITY_GROUP_RULES))
                .entity(outRules).build
    }

    @DELETE
    @Path("{id}") def delete(@PathParam("id") id: UUID) {
        api.deleteSecurityGroupRule(id)
    }

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_RULE_JSON_V1))
    def get(@PathParam("id") id: UUID): SecurityGroupRule = {
        val rule = api.getSecurityGroupRule(id)
        if (rule == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND, id))
        }
        rule
    }

    @GET
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_RULES_JSON_V1))
    def list: util.List[SecurityGroupRule] = api.getSecurityGroupRules
}