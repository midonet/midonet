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
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.host.TunnelZone;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.VTEP;
import org.midonet.event.topology.TunnelZoneEvent;
import org.midonet.util.serialization.SerializationException;
import org.midonet.cluster.backend.zookeeper.StateAccessException;

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
    public List<TunnelZone> list() throws StateAccessException,
            SerializationException {

        List<org.midonet.cluster.data.TunnelZone>
                tunnelZoneDataList = dataClient.tunnelZonesGetAll();
        List<TunnelZone> tunnelZones = new ArrayList<>();
        for (org.midonet.cluster.data.TunnelZone zoneData :
                tunnelZoneDataList) {
            TunnelZone zone = new TunnelZone(zoneData);
            zone.setBaseUri(getBaseUri());
            tunnelZones.add(zone);
        }
        return tunnelZones;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
                  MediaType.APPLICATION_JSON})
    public TunnelZone get(@PathParam("id") UUID id)
        throws StateAccessException,  SerializationException {

        if (!dataClient.tunnelZonesExists(id)) {
            throw new NotFoundHttpException();
        }

        org.midonet.cluster.data.TunnelZone zoneData =
                dataClient.tunnelZonesGet(id);
        TunnelZone zone = new TunnelZone(zoneData);
        zone.setBaseUri(getBaseUri());

        return zone;
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

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(TunnelZone tunnelZone)
            throws StateAccessException, SerializationException {

        validate(tunnelZone, TunnelZone.TunnelZoneCreateGroupSequence.class);

        UUID id = dataClient.tunnelZonesCreate(tunnelZone.toData());
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

        tunnelZone.setId(id);

        validate(tunnelZone, TunnelZone.TunnelZoneUpdateGroupSequence.class);

        dataClient.tunnelZonesUpdate(tunnelZone.toData());
        tunnelZoneEvent.update(id, dataClient.tunnelZonesGet(id));
    }

    @Path("/{id}" + ResourceUriBuilder.HOSTS)
    public TunnelZoneHostResource getTunnelZoneHostResource(
            @PathParam("id") UUID id) throws StateAccessException {
        if (!dataClient.tunnelZonesExists(id)) {
            throw new NotFoundHttpException();
        }
        return factory.getTunnelZoneHostResource(id);
    }
}
