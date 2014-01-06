/*
 * Copyright 2013 Midokura PTE Ltd.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.PoolMember;
import org.midonet.api.l4lb.VIP;
import org.midonet.api.rest_api.*;
import org.midonet.api.l4lb.Pool;
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

import static org.midonet.api.validation.MessageProperty.getMessage;


@RequestScoped
public class PoolResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(PoolResource.class);

    private final DataClient dataClient;

    @Inject
    public PoolResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context,
                          DataClient dataClient) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public List<Pool> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.Pool> dataPools = null;

        dataPools = dataClient.poolsGetAll();
        List<Pool> pools = new ArrayList<Pool>();
        if (dataPools != null) {
            for (org.midonet.cluster.data.l4lb.Pool dataPool :
                    dataPools) {
                Pool pool = new Pool(dataPool);
                pool.setBaseUri(getBaseUri());
                pools.add(pool);
            }
        }
        return pools;
    }

    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public Pool get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.l4lb.Pool poolData =
                dataClient.poolGet(id);
        if (poolData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "pool", id));
        }

        // Convert to the REST API DTO
        Pool pool = new Pool(poolData);
        pool.setBaseUri(getBaseUri());

        return pool;
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        org.midonet.cluster.data.l4lb.Pool PoolData =
                dataClient.poolGet(id);
        if (PoolData == null) {
            return;
        }
        dataClient.poolDelete(id);
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Pool pool)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException{

        try {
            UUID id = dataClient.poolCreate(pool.toData());
            return Response.created(
                    ResourceUriBuilder.getPool(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new BadRequestHttpException(getMessage(
                    MessageProperty.RESOURCE_EXISTS, "pool", pool.getId()));
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Pool pool)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        pool.setId(id);

        dataClient.poolUpdate(pool.toData());
    }

    @GET
    @PermitAll
    @Path("{id}" + ResourceUriBuilder.POOL_MEMBERS)
    @Produces({VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<PoolMember> listMembers(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        // TODO: Authorization.

        List<org.midonet.cluster.data.l4lb.PoolMember> dataMembers = null;
        try {
            dataMembers = dataClient.poolGetMembers(id);
        } catch (NoStatePathException ex) {
            if (ex.getPath().matches(".*pools.*pool_members")) {
                throw new NotFoundHttpException(getMessage(
                        MessageProperty.RESOURCE_NOT_FOUND, "pool", id));
            }
            throw ex;
        }

        List<PoolMember> members = new ArrayList<>(dataMembers.size());
        for (org.midonet.cluster.data.l4lb.PoolMember dataMember : dataMembers) {
            PoolMember member = new PoolMember(dataMember);
            member.setBaseUri(getBaseUri());
            members.add(member);
        }

        return members;
    }

    @GET
    @PermitAll
    @Path("{id}" + ResourceUriBuilder.VIPS)
    @Produces({VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<VIP> listVips(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        // TODO: Authorization

        List<org.midonet.cluster.data.l4lb.VIP> dataVips = null;
        try {
            dataVips = dataClient.poolGetVips(id);
        } catch (NoStatePathException ex) {
            if (ex.getPath().matches(".*pools.*pool_members")) {
                throw new NotFoundHttpException(getMessage(
                        MessageProperty.RESOURCE_NOT_FOUND, "pool", id));
            }
            throw ex;
        }

        List<VIP> vips = new ArrayList<>(dataVips.size());
        for (org.midonet.cluster.data.l4lb.VIP dataVip : dataVips) {
            VIP vip = new VIP(dataVip);
            vip.setBaseUri(getBaseUri());
            vips.add(vip);
        }

        return vips;
    }
}
