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
import org.midonet.cluster.data.neutron.Network;
import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.event.neutron.HealthMonitorEvent;
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

public class HealthMonitorResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
        HealthMonitorResource.class);
    private final static HealthMonitorEvent HEALTH_MONITOR_EVENT =
        new HealthMonitorEvent();

    private final LBaaSApi api;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context, LBaaSApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.create entered {}", healthMonitor);

        try {
            HealthMonitor createdHealthMonitor
                = api.createNeutronHealthMonitor(healthMonitor);
            HEALTH_MONITOR_EVENT.create(createdHealthMonitor.id,
                                        createdHealthMonitor);
            log.info("HealthMonitorResource.create exiting {}",
                     createdHealthMonitor);
            return Response.created(
                NeutronUriBuilder.getHealthMonitor(getBaseUri(),
                                                   createdHealthMonitor.id))
                .entity(healthMonitor).build();
        } catch (StatePathExistsException e) {
            log.error("Duplicate resource error", e);
            throw new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        }
    }

    @POST
    @Consumes(NeutronMediaType.HEALTH_MONITORS_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITORS_JSON_V1)
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
            return Response.created(NeutronUriBuilder.getHealthMonitors(
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
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
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
    @Produces(NeutronMediaType.HEALTH_MONITORS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<HealthMonitor> list()
        throws SerializationException, StateAccessException {
        log.info("HealthMonitorResource.list entered");
        List<HealthMonitor> healthMonitors = api.getHealthMonitors();
        return healthMonitors;
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
    @Produces(NeutronMediaType.HEALTH_MONITOR_JSON_V1)
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
