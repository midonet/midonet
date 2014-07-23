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

    private final static Logger log = LoggerFactory.getLogger(
        HealthMonitorResource.class);
    private final static HealthMonitorEvent HEALTH_MONITOR_EVENT =
        new HealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context, LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.create entered {}", healthMonitor);

        try {
            HealthMonitor createdHealthMonitor
                = api.createHealthMonitor(healthMonitor);
            HEALTH_MONITOR_EVENT.create(createdHealthMonitor.id,
                                        createdHealthMonitor);
            log.info("HealthMonitorResource.create exiting {}",
                     createdHealthMonitor);
            return Response.created(
                LBUriBuilder.getHealthMonitor(getBaseUri(),
                                              createdHealthMonitor.id))
                .entity(healthMonitor).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(LBMediaType.HEALTH_MONITORS_JSON_V1)
    @Produces(LBMediaType.HEALTH_MONITORS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response createBulk(List<HealthMonitor> healthMonitors)
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.createBulk entered");

        try {
            List<HealthMonitor> createdHealthMonitors
                = api.createHealthMonitorBulk(healthMonitors);
            for (HealthMonitor healthMonitor : createdHealthMonitors) {
                HEALTH_MONITOR_EVENT.create(healthMonitor.id, healthMonitor);
            }
            return Response.created(LBUriBuilder.getHealthMonitors(
                getBaseUri())).entity(createdHealthMonitors).build();
        } catch (StatePathExistsException e) {
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.delete entered {}", id);
        api.deleteHealthMonitor(id);
        HEALTH_MONITOR_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public HealthMonitor get(@PathParam("id") UUID id)
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.get entered {}", id);

        HealthMonitor healthMonitor = api.getHealthMonitor(id);
        if (healthMonitor == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("HealthMonitorResource.get exiting {}", healthMonitor);
        return healthMonitor;
    }

    @GET
    @Produces(LBMediaType.HEALTH_MONITORS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<HealthMonitor> list()
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.list entered");
        List<HealthMonitor> healthMonitors = api.getHealthMonitors();
        return healthMonitors;
    }

    @PUT
    @Path("{id}")
    @Consumes(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(LBMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id,
                           HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException,
               BridgeZkManager.VxLanPortIdUpdateException {
        log.info("HealthMonitorResource.update entered {}", healthMonitor);

        try {
            HealthMonitor updatedHealthMonitor
                = api.updateHealthMonitor(id, healthMonitor);
            HEALTH_MONITOR_EVENT.update(id, updatedHealthMonitor);
            log.info("HealthMonitorResource.update exiting {}",
                     updatedHealthMonitor);
            return Response.ok(
                NeutronUriBuilder.getNetwork(getBaseUri(),
                                             updatedHealthMonitor.id))
                .entity(healthMonitor).build();
        } catch (NoStatePathException e) {
            log.error("Resource does not exist", e);
            throw new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        }
    }
}
