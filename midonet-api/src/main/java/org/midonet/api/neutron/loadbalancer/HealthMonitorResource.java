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
import org.midonet.api.neutron.NeutronUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.loadbalancer.LBMediaType;
import org.midonet.cluster.data.neutron.LoadBalancerApi;
import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.event.neutron.HealthMonitorEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class HealthMonitorResource extends AbstractResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        HealthMonitorResource.class);
    private static final HealthMonitorEvent HEALTH_MONITOR_EVENT =
        new HealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @GET
    @Path("{id}")
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public HealthMonitor get(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        LOG.info("HealthMonitorResource.get entered {}", id);

        HealthMonitor healthMonitor = api.getHealthMonitor(id);
        if (healthMonitor == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        LOG.info("HealthMonitorResource.get exiting {}", healthMonitor);
        return healthMonitor;
    }

    @GET
    @Produces(LBMediaType.HEALTH_MONITORS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<HealthMonitor> list()
        throws SerializationException, StateAccessException {
        LOG.info("HealthMonitorResource.list entered");
        return api.getHealthMonitors();
    }

    @POST
    @Consumes(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response create(HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException {
        LOG.info("HealthMonitorResource.create entered {}", healthMonitor);

        try {
            api.createHealthMonitor(healthMonitor);
            HEALTH_MONITOR_EVENT.create(healthMonitor.id, healthMonitor);
            LOG.info("HealthMonitorResource.create exiting {}", healthMonitor);
            return Response.created(
                LBUriBuilder.getHealthMonitor(getBaseUri(), healthMonitor.id))
                .entity(healthMonitor).build();
        } catch (StatePathExistsException e) {
            LOG.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public final void delete(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        LOG.info("HealthMonitorResource.delete entered {}", id);
        api.deleteHealthMonitor(id);
        HEALTH_MONITOR_EVENT.delete(id);
    }

    @PUT
    @Path("{id}")
    @Consumes(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public final Response update(@PathParam("id") UUID id,
                                 HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        LOG.info("HealthMonitorResource.update entered {}", healthMonitor);

        try {
            api.updateHealthMonitor(id, healthMonitor);
            HEALTH_MONITOR_EVENT.update(id, healthMonitor);
            LOG.info("HealthMonitorResource.update exiting {}", healthMonitor);
            return Response.ok(
                NeutronUriBuilder.getNetwork(getBaseUri(), healthMonitor.id))
                .entity(healthMonitor).build();
        } catch (NoStatePathException e) {
            LOG.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
