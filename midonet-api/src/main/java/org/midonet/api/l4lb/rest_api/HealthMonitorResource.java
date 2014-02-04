/*
 * Copyright 2013 Midokura PTE Ltd.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.*;
import org.midonet.api.l4lb.HealthMonitor;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
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
public class HealthMonitorResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(HealthMonitorResource.class);

    private final DataClient dataClient;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context,
                          Validator validator, DataClient dataClient,
                          ResourceFactory factory) {
        super(config, uriInfo, context);
        this.dataClient = dataClient;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public List<HealthMonitor> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.HealthMonitor> dataHealthMonitors;

        dataHealthMonitors = dataClient.healthMonitorsGetAll();
        List<HealthMonitor> healthMonitors = new ArrayList<HealthMonitor>();
        if (dataHealthMonitors != null) {
            for (org.midonet.cluster.data.l4lb.HealthMonitor dataHealthMonitor :
                    dataHealthMonitors) {
                HealthMonitor healthMonitor
                        = new HealthMonitor(dataHealthMonitor);
                healthMonitor.setBaseUri(getBaseUri());
                healthMonitors.add(healthMonitor);
            }
        }
        return healthMonitors;
    }

    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public HealthMonitor get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.l4lb.HealthMonitor healthMonitorData =
                dataClient.healthMonitorGet(id);
        if (healthMonitorData == null) {
            throw new NotFoundHttpException(
                    getMessage(MessageProperty.RESOURCE_NOT_FOUND));
        }

        // Convert to the REST API DTO
        HealthMonitor healthMonitor = new HealthMonitor(healthMonitorData);
        healthMonitor.setBaseUri(getBaseUri());

        return healthMonitor;
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        org.midonet.cluster.data.l4lb.HealthMonitor healthMonitorData =
                dataClient.healthMonitorGet(id);
        if (healthMonitorData == null) {
            return;
        }
        dataClient.healthMonitorDelete(id);
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(HealthMonitor healthMonitor)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException{

        try {
            UUID id = dataClient.healthMonitorCreate(healthMonitor.toData());
            return Response.created(
                    ResourceUriBuilder.getHealthMonitor(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new StateAccessException();
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, HealthMonitor healthMonitor)
            throws StateAccessException,
            InvalidStateOperationException, SerializationException {

        healthMonitor.setId(id);

        dataClient.healthMonitorUpdate(healthMonitor.toData());
    }
}
