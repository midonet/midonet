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
import javax.ws.rs._
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{SECURITY_GROUPS, getUri}
import org.midonet.cluster.rest_api.neutron.models.SecurityGroup
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.neutron.NeutronMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.StateAccessException

class SecurityGroupResource @Inject() (uriInfo: UriInfo,
                                       api: NeutronZoomPlugin) {

    @POST
    @Consumes(Array(NeutronMediaType.SECURITY_GROUP_JSON_V1))
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_JSON_V1))
    def create(sg: SecurityGroup): Response = {
        val s = api.createSecurityGroup(sg)
        Response.created(getUri(uriInfo.getBaseUri, SECURITY_GROUPS, s.id))
                .entity(s).build
    }

    @POST
    @Consumes(Array(NeutronMediaType.SECURITY_GROUPS_JSON_V1))
    @Produces(Array(NeutronMediaType.SECURITY_GROUPS_JSON_V1))
    def createBulk(sgs: util.List[SecurityGroup]): Response = {
        val outSgs: util.List[SecurityGroup] = api.createSecurityGroupBulk(sgs)
        Response.created(getUri(uriInfo.getBaseUri, SECURITY_GROUPS))
                .entity(outSgs).build
    }

    @DELETE
    @Path("{id}")
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def delete(@PathParam("id") id: UUID): Unit = api.deleteSecurityGroup(id)

    @GET
    @Path("{id}")
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def get(@PathParam("id") id: UUID): SecurityGroup = {
        val sg = api.getSecurityGroup(id)
        if (sg == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        sg
    }

    @GET
    @Produces(Array(NeutronMediaType.SECURITY_GROUPS_JSON_V1))
    @throws(classOf[SerializationException])
    @throws(classOf[StateAccessException])
    def list: util.List[SecurityGroup] = api.getSecurityGroups

    @PUT
    @Path("{id}")
    @Consumes(Array(NeutronMediaType.SECURITY_GROUP_JSON_V1))
    @Produces(Array(NeutronMediaType.SECURITY_GROUP_JSON_V1))
    def update(@PathParam("id") id: UUID, sg: SecurityGroup): Response = {
        val s = api.updateSecurityGroup(id, sg)
        Response.ok(getUri(uriInfo.getBaseUri, SECURITY_GROUPS, s.id))
                .entity(s).build
    }
}