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
package org.midonet.api.neutron.loadbalancer;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LBMediaType;
import org.midonet.cluster.data.neutron.LoadBalancerApi;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.event.neutron.PoolMemberEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class MemberResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        MemberResource.class);
    private final static PoolMemberEvent POOL_MEMBER_EVENT =
        new PoolMemberEvent();

    private final LoadBalancerApi api;

    @Inject
    public MemberResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @GET
    @Path("{id}")
    @Produces(LBMediaType.MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Member get(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("MemberResource.get entered {}", id);

        Member member = api.getMember(id);
        if (member == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("MemberResource.get exiting {}", member);
        return member;
    }

    @GET
    @Produces(LBMediaType.MEMBERS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Member> list()
        throws SerializationException, StateAccessException {
        log.info("MemberResource.list entered");
        return api.getMembers();
    }

    @POST
    @Consumes(LBMediaType.MEMBER_JSON_V1)
    @Produces(LBMediaType.MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Member member)
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.create entered {}", member);

        try {
            api.createMember(member);
            POOL_MEMBER_EVENT.create(member.id, member);
            log.info("PoolMemberResource.create exiting {}", member);
            return Response.created(
                LBUriBuilder.getMember(getBaseUri(), member.id))
                .entity(member).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.delete entered {}", id);
        api.deleteMember(id);
        POOL_MEMBER_EVENT.delete(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(LBMediaType.MEMBER_JSON_V1)
    @Produces(LBMediaType.MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Member member)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        log.info("PoolMemberResource.update entered {}", member);

        try {
            api.updateMember(id, member);
            POOL_MEMBER_EVENT.update(id, member);
            log.info("PoolMemberResource.update exiting {}", member);
            return Response.ok(
                LBUriBuilder.getMember(getBaseUri(), member.id))
                .entity(member).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
