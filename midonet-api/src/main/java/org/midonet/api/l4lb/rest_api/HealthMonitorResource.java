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
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.conversion.HealthMonitorDataConverter;
import org.midonet.cluster.rest_api.conversion.PoolDataConverter;
import org.midonet.cluster.rest_api.models.HealthMonitor;
import org.midonet.cluster.rest_api.models.Pool;
import org.midonet.event.topology.HealthMonitorEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.l4lb.MappingStatusException;

import static org.midonet.cluster.rest_api.conversion.HealthMonitorDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.HealthMonitorDataConverter.toData;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAPPING_STATUS_IS_PENDING;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;


@RequestScoped
public class HealthMonitorResource extends AbstractResource {

    private final static HealthMonitorEvent healthMonitorEvent
            = new HealthMonitorEvent();

    @Inject
    public HealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                 SecurityContext context,
                                 DataClient dataClient, Validator validator) {
        super(config, uriInfo, context, dataClient, validator);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_HEALTH_MONITOR_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<HealthMonitor> list()
        throws StateAccessException, SerializationException,
               IllegalAccessException {

        List<org.midonet.cluster.data.l4lb.HealthMonitor> hmonitors;

        hmonitors = dataClient.healthMonitorsGetAll();
        List<HealthMonitor> healthMonitors = new ArrayList<>();
        if (hmonitors != null) {
            for (org.midonet.cluster.data.l4lb.HealthMonitor hmData : hmonitors) {
                healthMonitors.add(fromData(hmData, getBaseUri()));
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
            throws StateAccessException, SerializationException,
                   IllegalAccessException {

        org.midonet.cluster.data.l4lb.HealthMonitor healthMonitorData =
            dataClient.healthMonitorGet(id);
        if (healthMonitorData == null) {
            throw notFoundException(id, ResourceUris.HEALTH_MONITORS);
        }

        return HealthMonitorDataConverter.fromData(healthMonitorData,
                                                   getBaseUri());
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        try {
            dataClient.healthMonitorDelete(id);
            healthMonitorEvent.delete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                MediaType.APPLICATION_JSON })
    public Response create(HealthMonitor hm)
        throws StateAccessException, SerializationException {

        validate(hm);

        try {
            UUID id = dataClient.healthMonitorCreate(toData(hm));
            healthMonitorEvent.create(id, dataClient.healthMonitorGet(id));
            return Response.created(
                    ResourceUriBuilder.getHealthMonitor(getBaseUri(), id))
                    .build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(ex,
                    getMessage(RESOURCE_EXISTS, "health monitor", hm.id));
        }
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {

        healthMonitor.id = id;
        validate(healthMonitor);

        try {
            dataClient.healthMonitorUpdate(toData(healthMonitor));
            healthMonitorEvent.update(id, dataClient.healthMonitorGet(id));
        } catch (NoStatePathException ex) {
            throw notFoundException(id, "health monitor");
        } catch (MappingStatusException ex) {
            throw new ServiceUnavailableHttpException(
                getMessage(MAPPING_STATUS_IS_PENDING, ex.getMessage()));
        }
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}" + ResourceUriBuilder.POOLS)
    @Produces({VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    public List<Pool> listPools(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException,
               IllegalAccessException {

        List<org.midonet.cluster.data.l4lb.Pool> dataPools;
        try {
            dataPools = dataClient.healthMonitorGetPools(id);
        } catch (NoStatePathException ex) {
            throw notFoundException(id, "health monitor");
        }

        List<Pool> pools = new ArrayList<>(dataPools.size());
        for (org.midonet.cluster.data.l4lb.Pool dataPool : dataPools) {
            pools.add(PoolDataConverter.fromData(dataPool, getBaseUri()));
        }

        return pools;
    }
}
