/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.event.neutron.PoolEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class PoolResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        PoolResource.class);
    private final static PoolEvent POOL_EVENT = new PoolEvent();

    private final LoadBalancerApi api;

    @Inject
    public PoolResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(LBMediaType.POOL_JSON_V1)
    @Produces(LBMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(Pool pool)
        throws SerializationException, StateAccessException {
        log.info("PoolResource.create entered {}", pool);

        try {
            Pool newPool = api.createPool(pool);
            POOL_EVENT.create(newPool.id, newPool);
            log.info("PoolResource.create exiting {}", newPool);
            return Response.created(
                LBUriBuilder.getPool(getBaseUri(), newPool.id))
                .entity(newPool).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(LBMediaType.POOLS_JSON_V1)
    @Produces(LBMediaType.POOLS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<Pool> pools)
        throws SerializationException, StateAccessException {
        log.info("PoolResource.createBulk entered");

        try {
            List<Pool> createdPools = api.createPoolBulk(pools);
            for (Pool pool : createdPools) {
                POOL_EVENT.create(pool.id, pool);
            }
            return Response.created(LBUriBuilder.getPools(
                getBaseUri())).entity(createdPools).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("PoolResource.delete entered {}", id);
        api.deletePool(id);
        POOL_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(LBMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Pool get(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("PoolResource.get entered {}", id);

        Pool pool = api.getPool(id);
        if (pool == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("PoolResource.get exiting {}", pool);
        return pool;
    }

    @GET
    @Produces(LBMediaType.POOLS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<Pool> list()
        throws SerializationException, StateAccessException {
        log.info("PoolResource.list entered");
        List<Pool> pools = api.getPools();
        return pools;
    }

    @PUT
    @Path("{id}")
    @Consumes(LBMediaType.POOL_JSON_V1)
    @Produces(LBMediaType.POOL_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, Pool pool)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        log.info("PoolResource.update entered {}", pool);

        try {
            // TODO: implement api wrapper
            Pool updatePool = api.updatePool(id, pool);
            POOL_EVENT.update(id, updatePool);
            log.info("PoolResource.update exiting {}", updatePool);
            return Response.ok(
                LBUriBuilder.getPool(getBaseUri(), updatePool.id))
                .entity(pool).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
