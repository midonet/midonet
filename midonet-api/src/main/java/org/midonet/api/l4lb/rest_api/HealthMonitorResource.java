/*
 * Copyright 2013 Midokura PTE Ltd.
 */

package org.midonet.api.l4lb.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.Pool;
import org.midonet.api.rest_api.*;
import org.midonet.api.l4lb.HealthMonitor;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;


@RequestScoped
public class HealthMonitorResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(HealthMonitorResource.class);

    private final Validator validator;
    private final DataClient dataClient;

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                          SecurityContext context,
                          Validator validator, DataClient dataClient,
                          ResourceFactory factory) {
        super(config, uriInfo, context);
        this.validator = validator;
        this.dataClient = dataClient;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_HEALTH_MONITOR_COLLECTION_JSON,
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
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public HealthMonitor get(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.l4lb.HealthMonitor healthMonitorData =
                dataClient.healthMonitorGet(id);
        if (healthMonitorData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "health monitor", id));
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
            throws StateAccessException, SerializationException {

        try {
            dataClient.healthMonitorDelete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(HealthMonitor healthMonitor)
            throws StateAccessException, SerializationException {

        Set<ConstraintViolation<HealthMonitor>> violations =
                validator.validate(healthMonitor);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        try {
            UUID id = dataClient.healthMonitorCreate(healthMonitor.toData());
            return Response.created(
                    ResourceUriBuilder.getHealthMonitor(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    RESOURCE_EXISTS, "health monitor", healthMonitor.getId()));
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, HealthMonitor healthMonitor)
            throws StateAccessException, SerializationException {

        healthMonitor.setId(id);

        try {
            dataClient.healthMonitorUpdate(healthMonitor.toData());
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex);
        }
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}" + ResourceUriBuilder.POOLS)
    @Produces({VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
            MediaType.APPLICATION_JSON})
    public List<Pool> listPools(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.l4lb.Pool> dataPools = null;
        try {
            dataPools = dataClient.healthMonitorGetPools(id);
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(ex);
        }

        List<Pool> pools = new ArrayList<>(dataPools.size());
        for (org.midonet.cluster.data.l4lb.Pool dataPool : dataPools) {
            Pool pool = new Pool(dataPool);
            pool.setBaseUri(getBaseUri());
            pools.add(pool);
        }

        return pools;
    }
}
