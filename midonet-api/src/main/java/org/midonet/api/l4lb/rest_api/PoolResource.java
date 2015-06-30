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

package org.midonet.api.l4lb.rest_api;

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

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.Pool;
import org.midonet.event.topology.PoolEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;

import static org.midonet.cluster.rest_api.conversion.PoolDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.PoolDataConverter.toData;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAPPING_DISASSOCIATION_IS_REQUIRED;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAPPING_STATUS_IS_PENDING;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class PoolResource extends AbstractResource {

    private final PoolEvent poolEvent = new PoolEvent();

    private final ResourceFactory factory;

    @Inject
    public PoolResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context,
                        DataClient dataClient,
                        ResourceFactory factory,
                        Validator validator) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
    public List<Pool> list() throws StateAccessException,
                                    SerializationException,
                                    IllegalAccessException {

        List<org.midonet.cluster.data.l4lb.Pool> dataPools;

        dataPools = dataClient.poolsGetAll();
        List<Pool> pools = new ArrayList<>();
        if (dataPools != null) {
            for (org.midonet.cluster.data.l4lb.Pool dataPool :
                    dataPools) {
                pools.add(fromData(dataPool, getBaseUri()));
            }
        }
        return pools;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_POOL_JSON,
            MediaType.APPLICATION_JSON })
    public Pool get(@PathParam("id") UUID id) throws StateAccessException,
                                                     SerializationException,
                                                     IllegalAccessException {

        org.midonet.cluster.data.l4lb.Pool poolData =
            dataClient.poolGet(id);
        if (poolData == null) {
            throw notFoundException(id, "pool");
        }

        // Convert to the REST API DTO
        return fromData(poolData, getBaseUri());
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        try {
            dataClient.poolDelete(id);
            poolEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(Pool pool)
            throws StateAccessException, SerializationException {
        // `status` defaults to UP and users can't change it through the API.
        pool.status = LBStatus.ACTIVE;
        validate(pool);

        try {
            UUID id = dataClient.poolCreate(toData(pool));
            poolEvent.create(id, dataClient.poolGet(id));
            return Response.created(
                    ResourceUriBuilder.getPool(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex,
                    getMessage(RESOURCE_EXISTS, "pool", pool.id));
        } catch (NoStatePathException ex) {
            throw notFoundException(pool.healthMonitorId, "health monitor");
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_POOL_JSON,
                MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Pool pool)
            throws StateAccessException, SerializationException {
        pool.id = id;
        validate(pool);

        try {
            org.midonet.cluster.data.l4lb.Pool oldData = dataClient.poolGet(id);
            if (oldData == null) {
                throw notFoundException(id, "pool");
            }

            // DISALLOW changing this from the API
            pool.status = oldData.getStatus();

            dataClient.poolUpdate(toData(pool));
            poolEvent.update(id, dataClient.poolGet(id));
        } catch (NoStatePathException ex) {
            throw notFoundException(pool.healthMonitorId, "health monitor");
        } catch (MappingViolationException ex) {
            throw new BadRequestHttpException(ex, MAPPING_DISASSOCIATION_IS_REQUIRED);
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
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

        @Inject
        public LoadBalancerPoolResource(RestApiConfig config, UriInfo uriInfo,
                                        SecurityContext context,
                                        DataClient dataClient,
                                        Validator validator,
                                        @Assisted UUID id) {
            super(config, uriInfo, context, dataClient, validator);
            this.loadBalancerId = id;
        }

        @GET
        @RolesAllowed({ AuthRole.ADMIN })
        @Produces({ VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
                    MediaType.APPLICATION_JSON })
        public List<Pool> list() throws StateAccessException,
                                        SerializationException,
                                        IllegalAccessException{

            List<org.midonet.cluster.data.l4lb.Pool> dataPools;

            dataPools = dataClient.loadBalancerGetPools(loadBalancerId);
            List<Pool> pools = new ArrayList<>();
            if (dataPools != null) {
                for (org.midonet.cluster.data.l4lb.Pool dataPool : dataPools) {
                    pools.add(fromData(dataPool, getBaseUri()));
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
            pool.loadBalancerId = loadBalancerId;
            // `status` defaults to UP and users can't change it through the API.
            pool.status = LBStatus.ACTIVE;
            validate(pool);

            try {
                UUID id = dataClient.poolCreate(toData(pool));
                return Response.created(
                        ResourceUriBuilder.getPool(getBaseUri(), id))
                        .build();
            } catch (StatePathExistsException ex) {
                throw new ConflictHttpException(ex,
                        getMessage(RESOURCE_EXISTS, "pool", pool.id));
            } catch (NoStatePathException ex) {
                throw badReqOrNotFoundException(ex, loadBalancerId);
            } catch (MappingStatusException ex) {
                throw new ServiceUnavailableHttpException(
                    getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
            }
        }
    }
}
