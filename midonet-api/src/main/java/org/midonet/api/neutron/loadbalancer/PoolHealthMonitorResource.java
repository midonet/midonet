/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron.loadbalancer;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LBMediaType;
import org.midonet.cluster.data.neutron.LoadBalancerApi;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.event.neutron.PoolHealthMonitorEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class PoolHealthMonitorResource extends AbstractResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        PoolHealthMonitorResource.class);
    private static final PoolHealthMonitorEvent POOL_HEALTH_MONITOR_EVENT
        = new PoolHealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public PoolHealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(LBMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    @Produces(LBMediaType.POOL_HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response create(PoolHealthMonitor poolHealthMonitor)
        throws SerializationException, StateAccessException {
        LOG.info("PoolHealthMonitorResource.create entered {}",
                 poolHealthMonitor);

        try {
            api.createPoolHealthMonitor(poolHealthMonitor);
            POOL_HEALTH_MONITOR_EVENT.create(poolHealthMonitor.poolId,
                                             poolHealthMonitor.healthMonitor.id);
            LOG.info("PoolHealthMonitorResource.create exiting {}",
                     poolHealthMonitor);
            return Response.created(
                LBUriBuilder.getPoolHealthMonitor(getBaseUri()))
                .entity(poolHealthMonitor).build();
        } catch (StatePathExistsException e) {
            LOG.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public final void delete(PoolHealthMonitor poolHealthMonitor)
        throws SerializationException, StateAccessException {
        LOG.info("PoolHealthMonitorResource.delete entered {}",
                 poolHealthMonitor);
        api.deletePoolHealthMonitor(poolHealthMonitor);
        POOL_HEALTH_MONITOR_EVENT.delete(poolHealthMonitor.poolId,
                                         poolHealthMonitor.healthMonitor.id);
    }
}
