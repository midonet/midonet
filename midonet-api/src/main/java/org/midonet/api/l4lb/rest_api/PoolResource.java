/*
 * Copyright 2013 Midokura PTE Ltd.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.Pool;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.rest_api.ServiceUnavailableHttpException;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.neutron.LBaaSApi;
import org.midonet.event.topology.PoolEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
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
public class PoolResource extends AbstractResource {

    private final PoolEvent poolEvent = new PoolEvent();

    private final ResourceFactory factory;

    private final LBaaSApi api;

    @Inject
    public PoolResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context,
                        LBaaSApi api,
                        ResourceFactory factory,
                        Validator validator) {
        super(config, uriInfo, context, null, validator);
        this.factory = factory;
        this.api = api;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Pool> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.Pool> dataPools = null;

        dataPools = api.poolsGetAll();
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
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public Pool get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.l4lb.Pool poolData =
                api.poolGet(id);
        if (poolData == null)
            throwNotFound(id, "pool");

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

        try {
            api.poolDelete(id);
            poolEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Pool pool)
            throws StateAccessException, SerializationException {
        // `status` defaults to UP and users can't change it through the API.
        pool.setStatus(LBStatus.ACTIVE.toString());
        validate(pool);

        try {
            UUID id = api.poolCreate(pool.toData());
            poolEvent.create(id, api.poolGet(id));
            return Response.created(
                    ResourceUriBuilder.getPool(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex,
                    getMessage(RESOURCE_EXISTS, "pool", pool.getId()));
        } catch (NoStatePathException ex) {
            throw new BadRequestHttpException(ex);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Pool pool)
            throws StateAccessException, SerializationException {
        pool.setId(id);
        validate(pool);

        try {
            api.poolUpdate(pool.toData());
            poolEvent.update(id, api.poolGet(id));
        } catch (NoStatePathException ex) {
            throw badReqOrNotFoundException(ex, id);
        } catch (MappingViolationException ex) {
            throw new BadRequestHttpException(ex,
                MessageProperty.MAPPING_DISASSOCIATION_IS_REQUIRED);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(ex);
        }
    }

    /**
     * Delegate the request handlings of the pool members to the sub resource.
     *
     * @param id The UUID of the pool which has the associated pool members.
     * @return PoolMemberResource.PoolPoolMemberResource instance.
     */
    @Path("{id}" + ResourceUriBuilder.POOL_MEMBERS)
    public PoolMemberResource.PoolPoolMemberResource getPoolMemberResource(
            @PathParam("id") UUID id) {
        return factory.getPoolPoolMemberResource(id);
    }

    /**
     * Delegate the request handlings of the VIPs to the sub resource.
     *
     * @param id The UUID of the pool which has the associated VIPs.
     * @return PoolMemberResource.PoolPoolMemberResource instance.
     */
    @Path("{id}" + ResourceUriBuilder.VIPS)
    public VipResource.PoolVipResource getVipResource(
            @PathParam("id") UUID id) {
        return factory.getPoolVipResource(id);
    }

    /**
     * Sub-resource class for load balancer's pools.
     */
    @RequestScoped
    public static class LoadBalancerPoolResource extends AbstractResource {
        private final UUID loadBalancerId;

        private final LBaaSApi api;

        @Inject
        public LoadBalancerPoolResource(RestApiConfig config, UriInfo uriInfo,
                                        SecurityContext context,
                                        LBaaSApi api,
                                        Validator validator,
                                        @Assisted UUID id) {
            super(config, uriInfo, context, null, validator);
            this.loadBalancerId = id;
            this.api = api;
        }

        @GET
        @RolesAllowed({ AuthRole.ADMIN })
        @Produces({ VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Pool> list()
                throws StateAccessException, SerializationException {

            List<org.midonet.cluster.data.l4lb.Pool> dataPools = null;

            dataPools = api.loadBalancerGetPools(loadBalancerId);
            List<Pool> pools = new ArrayList<Pool>();
            if (dataPools != null) {
                for (org.midonet.cluster.data.l4lb.Pool dataPool : dataPools) {
                    Pool pool = new Pool(dataPool);
                    pool.setBaseUri(getBaseUri());
                    pools.add(pool);
                }
            }
            return pools;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(Pool pool)
                throws StateAccessException, SerializationException {
            pool.setLoadBalancerId(loadBalancerId);
            // `status` defaults to UP and users can't change it through the API.
            pool.setStatus(LBStatus.ACTIVE.toString());
            validate(pool);

            try {
                UUID id = api.poolCreate(pool.toData());
                return Response.created(
                        ResourceUriBuilder.getPool(getBaseUri(), id))
                        .build();
            } catch (StatePathExistsException ex) {
                throw new ConflictHttpException(ex,
                        getMessage(RESOURCE_EXISTS, "pool", pool.getId()));
            } catch (NoStatePathException ex) {
                throw badReqOrNotFoundException(ex, loadBalancerId);
            } catch (MappingStatusException ex) {
                throw new ServiceUnavailableHttpException(ex);
            }
        }
    }
}
