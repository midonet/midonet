/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.PoolMember;
import org.midonet.api.rest_api.*;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.POOL_MEMBER_WEIGHT_NEGATIVE;
import static org.midonet.api.validation.MessageProperty.getMessage;


@RequestScoped
public class PoolMemberResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(PoolMemberResource.class);

    private final DataClient dataClient;

    @Inject
    public PoolMemberResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context,
                              DataClient dataClient) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PoolMember> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.PoolMember> dataPoolMembers = null;

        dataPoolMembers = dataClient.poolMembersGetAll();
        List<PoolMember> poolMembers = new ArrayList<PoolMember>();
        if (dataPoolMembers != null) {
            for (org.midonet.cluster.data.l4lb.PoolMember dataPoolMember :
                    dataPoolMembers) {
                PoolMember poolMember = new PoolMember(dataPoolMember);
                poolMember.setBaseUri(getBaseUri());
                poolMembers.add(poolMember);
            }
        }
        return poolMembers;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_POOL_MEMBER_JSON,
            MediaType.APPLICATION_JSON })
    public PoolMember get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.l4lb.PoolMember PoolMemberData =
                dataClient.poolMemberGet(id);
        if (PoolMemberData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "pool member", id));
        }

        // Convert to the REST API DTO
        PoolMember PoolMember = new PoolMember(PoolMemberData);
        PoolMember.setBaseUri(getBaseUri());

        return PoolMember;
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        try {
            dataClient.poolMemberDelete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so do nothing.
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_POOL_MEMBER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(PoolMember poolMember)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException{

        if (poolMember.getWeight() < 0) {
            throw new BadRequestHttpException(getMessage(POOL_MEMBER_WEIGHT_NEGATIVE));
        } else if (poolMember.getWeight() == 0) {
            poolMember.setWeight(1);
        }

        try {
            UUID id = dataClient.poolMemberCreate(poolMember.toData());
            return Response.created(
                    ResourceUriBuilder.getPoolMember(getBaseUri(), id))
                    .build();
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex);
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex);
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_POOL_MEMBER_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, PoolMember poolMember)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        if (poolMember.getWeight() < 0) {
            throw new BadRequestHttpException(
                    getMessage(POOL_MEMBER_WEIGHT_NEGATIVE));
        } else if (poolMember.getWeight() == 0) {
            poolMember.setWeight(1);
        }

        poolMember.setId(id);
        try {
            dataClient.poolMemberUpdate(poolMember.toData());
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex);
        }
    }
}
