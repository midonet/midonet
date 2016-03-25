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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Member;
import org.midonet.cluster.services.rest_api.neutron.plugin.LoadBalancerApi;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class MemberResource {

    private final LoadBalancerApi api;
    private final URI baseUri;
    private final UriInfo uriInfo;

    @Inject
    public MemberResource(UriInfo uriInfo, LoadBalancerApi api) {
        this.api = api;
        this.baseUri = uriInfo.getBaseUri();
        this.uriInfo = uriInfo;
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.MEMBER_JSON_V1)
    public Member get(@PathParam("id") UUID id) {
        Member member = api.getMember(id);
        if (member == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }
        return member;
    }

    @GET
    @Produces(NeutronMediaType.MEMBERS_JSON_V1)
    public List<Member> list() {
        List<String> mIds = uriInfo.getQueryParameters().get("id");
        if (mIds == null) {
            return api.getMembers();
        } else {
            List<UUID> memberIds = new ArrayList<>();
            for(String mId : mIds) {
                try {
                    memberIds.add(UUID.fromString(mId));
                } catch (IllegalArgumentException ex) {
                    // we can ignore these
                }
            }
            return api.getMembers(memberIds);
        }
    }

    @POST
    @Consumes(NeutronMediaType.MEMBER_JSON_V1)
    @Produces(NeutronMediaType.MEMBER_JSON_V1)
    public Response create(Member member) {
        api.createMember(member);
        return Response.created(NeutronUriBuilder.getMember(baseUri, member.id))
                       .entity(member).build();
    }

    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id) {
        api.deleteMember(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.MEMBER_JSON_V1)
    @Produces(NeutronMediaType.MEMBER_JSON_V1)
    public Response update(@PathParam("id") UUID id, Member member) {
        api.updateMember(id, member);
        return Response.ok(NeutronUriBuilder.getMember(baseUri, member.id))
                       .entity(member).build();
    }
}
