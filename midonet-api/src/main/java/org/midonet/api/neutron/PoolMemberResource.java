/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;

import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.NeutronMediaType;
import org.midonet.cluster.data.neutron.LBaaSApi;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.event.neutron.PoolMemberEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

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

public class PoolMemberResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        PoolMemberResource.class);
    private final static PoolMemberEvent POOL_MEMBER_EVENT =
        new PoolMemberEvent();

    private final LBaaSApi api;

    @Inject
    public PoolMemberResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context, LBaaSApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.POOL_MEMBER_JSON_V1)
    @Produces(NeutronMediaType.POOL_MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Member member)
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.create entered {}", member);

        try {
            Member createdMember = api.createNeutronMember(member);
            POOL_MEMBER_EVENT.create(member.id, member);
            log.info("PoolMemberResource.create exiting {}", createdMember);
            return Response.created(
                NeutronUriBuilder.getMember(getBaseUri(), createdMember.id))
                .entity(createdMember).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.POOL_MEMBERS_JSON_V1)
    @Produces(NeutronMediaType.POOL_MEMBERS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Member> members)
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.createBulk entered");

        try {
            List<Member> createdMembers = api.createMemberBulk(members);
            for (Member member : createdMembers) {
                POOL_MEMBER_EVENT.create(member.id, member);
            }
            return Response.created(NeutronUriBuilder.getMembers(
                getBaseUri())).entity(createdMembers).build();
        } catch (StatePathExistsException e) {
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

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.POOL_MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Member get(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.get entered {}", id);

        Member member = api.getMember(id);
        if (member == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("MemberResource.get exiting {}", member);
        return member;
    }

    @GET
    @Produces(NeutronMediaType.POOL_MEMBERS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Member> list()
        throws SerializationException, StateAccessException {
        log.info("PoolMemberResource.list entered");
        List<Member> members = api.getMembers();
        return members;
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.POOL_MEMBER_JSON_V1)
    @Produces(NeutronMediaType.POOL_MEMBER_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Member member)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        log.info("PoolMemberResource.update entered {}", member);

        try {
            Member updatedMember = api.updateMember(id, member);
            POOL_MEMBER_EVENT.update(id, updatedMember);
            log.info("PoolMemberResource.update exiting {}", updatedMember);
            return Response.ok(
                NeutronUriBuilder.getMember(getBaseUri(), updatedMember.id))
                .entity(updatedMember).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
