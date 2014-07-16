/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.PoolMember;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.rest_api.ServiceUnavailableHttpException;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.neutron.LBaaSApi;
import org.midonet.event.topology.PoolMemberEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.getMessage;

@RequestScoped
public class PoolMemberResource extends AbstractResource {

    private final PoolMemberEvent poolMemberEvent = new PoolMemberEvent();

    private final LBaaSApi api;

    @Inject
    public PoolMemberResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context, LBaaSApi api,
                              Validator validator) {
        super(config, uriInfo, context, null, validator);
        this.api = api;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<PoolMember> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.PoolMember> dataPoolMembers = null;

        dataPoolMembers = api.poolMembersGetAll();
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
                api.poolMemberGet(id);
        if (PoolMemberData == null)
            throwNotFound(id, "pool member");

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
            api.poolMemberDelete(id);
            poolMemberEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so do nothing.
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_POOL_MEMBER_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(PoolMember poolMember)
            throws StateAccessException, SerializationException {
        // `status` defaults to UP and users can't change it through the API.
        poolMember.setStatus(LBStatus.ACTIVE.toString());
        validate(poolMember);

        try {
            UUID id = api.poolMemberCreate(poolMember.toData());
            poolMemberEvent.create(id, api.poolMemberGet(id));
            return Response.created(
                    ResourceUriBuilder.getPoolMember(getBaseUri(), id))
                    .build();
        } catch (NoStatePathException ex) {
            throw new BadRequestHttpException(ex);
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex, getMessage(
                    RESOURCE_EXISTS, "pool member", poolMember.getId()));
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
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
        poolMember.setId(id);
        validate(poolMember);

        try {
            // Ignore `address`, `protocolPort` and `status` property
            // populated by users.
            if (api.poolMemberExists(id)) {
                org.midonet.cluster.data.l4lb.PoolMember oldPoolMember =
                        api.poolMemberGet(id);
                poolMember.setAddress(oldPoolMember.getAddress());
                poolMember.setProtocolPort(oldPoolMember.getProtocolPort());
                poolMember.setStatus(oldPoolMember.getStatus().toString());
            }

            api.poolMemberUpdate(poolMember.toData());
            poolMemberEvent.update(id, api.poolMemberGet(id));
        } catch (NoStatePathException ex) {
            throw badReqOrNotFoundException(ex, id);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
        }
    }

    /**
     * Sub-resource class for load pool's pool members.
     */
    @RequestScoped
    public static class PoolPoolMemberResource extends AbstractResource {
        private final UUID poolId;

        private final LBaaSApi api;

        @Inject
        public PoolPoolMemberResource(RestApiConfig config, UriInfo uriInfo,
                                      SecurityContext context,
                                      LBaaSApi api,
                                      Validator validator,
                                      @Assisted UUID id) {
            super(config, uriInfo, context, null, validator);
            this.poolId = id;
            this.api = api;
        }

        @GET
        @RolesAllowed({ AuthRole.ADMIN })
        @Produces({ VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PoolMember> list()
                throws StateAccessException, SerializationException {

            List<org.midonet.cluster.data.l4lb.PoolMember> dataPoolMembers;
            try {
                dataPoolMembers = api.poolGetMembers(poolId);
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex);
            }

            List<PoolMember> poolMembers = new ArrayList<>();
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

        @POST
        @RolesAllowed({ AuthRole.ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_POOL_MEMBER_JSON,
                MediaType.APPLICATION_JSON})
        public Response create(PoolMember poolMember)
                throws StateAccessException,
                InvalidStateOperationException, SerializationException {
            poolMember.setPoolId(poolId);
            // `status` defaults to UP and users can't change it through the API.
            poolMember.setStatus(LBStatus.ACTIVE.toString());
            validate(poolMember);

            try {
                UUID id = api.poolMemberCreate(poolMember.toData());
                return Response.created(
                        ResourceUriBuilder.getPoolMember(getBaseUri(), id))
                        .build();
            } catch (StatePathExistsException ex) {
                throw new ConflictHttpException(ex, getMessage(
                        MessageProperty.RESOURCE_EXISTS, "pool member"));
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex);
            } catch (MappingStatusException ex) {
                throw new ServiceUnavailableHttpException(ex);
            }
        }
    }
}
