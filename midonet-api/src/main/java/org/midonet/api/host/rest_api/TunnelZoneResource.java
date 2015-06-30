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
package org.midonet.api.host.rest_api;

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
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.TunnelZone;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.event.topology.TunnelZoneEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.conversion.TunnelZoneDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.TunnelZoneDataConverter.toData;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

@RequestScoped
public class TunnelZoneResource extends AbstractResource {

    private final static TunnelZoneEvent tunnelZoneEvent =
            new TunnelZoneEvent();

    private final ResourceFactory factory;

    @Inject
    public TunnelZoneResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context, DataClient dataClient,
                              Validator validator, ResourceFactory factory) {
        super(config, uriInfo, context, dataClient, validator);
        this.factory = factory;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<TunnelZone> list() throws Exception {

        List<org.midonet.cluster.data.TunnelZone>
                tzDataList = dataClient.tunnelZonesGetAll();
        List<TunnelZone> tunnelZones = new ArrayList<>();
        for (org.midonet.cluster.data.TunnelZone zoneData : tzDataList) {
            tunnelZones.add(fromData(zoneData, getBaseUri()));
        }
        return tunnelZones;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
                  MediaType.APPLICATION_JSON})
    public TunnelZone get(@PathParam("id") UUID id) throws Exception {

        if (!dataClient.tunnelZonesExists(id)) {
            throw notFoundException(id, "tunnel zone");
        }
        return fromData(dataClient.tunnelZonesGet(id), getBaseUri());
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.TunnelZone zoneData =
                dataClient.tunnelZonesGet(id);
        if (zoneData == null) {
            return;
        }

        for (VTEP vtep : dataClient.vtepsGetAll()) {
            if (vtep.getTunnelZoneId().equals(id)) {
                throw new ConflictHttpException(
                    "Can't delete tunnel zone: used by VTEP " + vtep.getId());
            }
        }

        dataClient.tunnelZonesDelete(id);
        tunnelZoneEvent.delete(id);
    }

    private void throwIfNameUsed(String name) throws
                                              SerializationException,
                                              StateAccessException,
                                              BadRequestHttpException {
        for (org.midonet.cluster.data.TunnelZone tz :
            dataClient.tunnelZonesGetAll()) {
            if (tz.getName().equalsIgnoreCase(name)) {
                throw new BadRequestHttpException(
                    getMessage(MessageProperty.UNIQUE_TUNNEL_ZONE_NAME_TYPE));
            }
        }
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(TunnelZone tunnelZone)
            throws StateAccessException, SerializationException {

        validate(tunnelZone);

        throwIfNameUsed(tunnelZone.name);

        UUID id = dataClient.tunnelZonesCreate(toData(tunnelZone));
        tunnelZoneEvent.create(id, dataClient.tunnelZonesGet(id));
        return Response.created(
                ResourceUriBuilder.getTunnelZone(getBaseUri(), id))
                .build();
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
            MediaType.APPLICATION_JSON })
    @Path("{id}")
    public void update(@PathParam("id") UUID id, TunnelZone tunnelZone)
            throws StateAccessException, SerializationException {

        tunnelZone.id = id;

        validate(tunnelZone);

        throwIfNameUsed(tunnelZone.name);

        dataClient.tunnelZonesUpdate(toData(tunnelZone));
        tunnelZoneEvent.update(id, dataClient.tunnelZonesGet(id));
    }

    @Path("/{id}" + ResourceUriBuilder.HOSTS)
    public TunnelZoneHostResource getTunnelZoneHostResource(
            @PathParam("id") UUID id) throws StateAccessException {
        if (!dataClient.tunnelZonesExists(id)) {
            throw notFoundException(id, "tunnel zone");
        }
        return factory.getTunnelZoneHostResource(id);
    }
}
