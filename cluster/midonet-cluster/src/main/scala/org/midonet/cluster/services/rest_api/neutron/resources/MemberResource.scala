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
import javax.ws.rs.{Consumes, DELETE, GET, POST, PUT, Path, PathParam, Produces}

import com.google.inject.Inject
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.NeutronResourceUris.{MEMBERS, getUri}
import org.midonet.cluster.rest_api.neutron.models.Member
import org.midonet.cluster.rest_api.validation.MessageProperty.{RESOURCE_NOT_FOUND, getMessage}
import org.midonet.cluster.services.rest_api.neutron.LBMediaType
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class MemberResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @GET
    @Path("{id}")
    @Produces(Array(LBMediaType.MEMBER_JSON_V1))
    def get(@PathParam("id") id: UUID): Member = {
        val member: Member = api.getMember(id)
        if (member == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND))
        }
        member
    }

    @GET
    @Produces(Array(LBMediaType.MEMBERS_JSON_V1))
    def list: util.List[Member] = api.getMembers

    @POST
    @Consumes(Array(LBMediaType.MEMBER_JSON_V1))
    @Produces(Array(LBMediaType.MEMBER_JSON_V1))
    def create(member: Member): Response = {
        api.createMember(member)
        Response.created(getUri(uriInfo.getBaseUri, MEMBERS, member.id))
                .entity(member).build
    }

    @DELETE
    @Path("{id}") def delete(@PathParam("id") id: UUID) {
        api.deleteMember(id)
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(LBMediaType.MEMBER_JSON_V1))
    @Produces(Array(LBMediaType.MEMBER_JSON_V1))
    def update(@PathParam("id") id: UUID, member: Member): Response = {
        api.updateMember(id, member)
        Response.ok(getUri(uriInfo.getBaseUri, MEMBERS, member.id))
                .entity(member).build
    }

}