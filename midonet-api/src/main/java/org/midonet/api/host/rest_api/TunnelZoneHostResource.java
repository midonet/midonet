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
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.TunnelZoneHost;
import org.midonet.event.topology.TunnelZoneEvent;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.conversion.TunnelZoneHostDataConverter.fromData;
import static org.midonet.cluster.rest_api.conversion.TunnelZoneHostDataConverter.toData;
import static org.midonet.cluster.rest_api.validation.MessageProperty.HOST_ID_IS_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.TUNNEL_ZONE_ID_IS_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.TUNNEL_ZONE_MEMBER_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

/**
 * REST API handler for tunnel zone - host mapping.
 */
@RequestScoped
public class TunnelZoneHostResource extends AbstractResource {

    private final static TunnelZoneEvent tunnelZoneEvent =
            new TunnelZoneEvent();
    private final UUID tunnelZoneId;

    @Inject
    public TunnelZoneHostResource(RestApiConfig config, UriInfo uriInfo,
                                  SecurityContext context,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID tunnelZoneId) {
        super(config, uriInfo, context, dataClient, validator);
        this.tunnelZoneId = tunnelZoneId;
    }

    private <T extends TunnelZoneHost> Response createTunnelZoneHost(T tzHost)
            throws StateAccessException, SerializationException {

        tzHost.tunnelZoneId = tunnelZoneId;

        validate(tzHost);

        if (!dataClient.hostsExists(tzHost.hostId)) {
            throw new NotFoundHttpException(getMessage(HOST_ID_IS_INVALID));
        }
        if (!dataClient.tunnelZonesExists(tunnelZoneId)) {
            throw new BadRequestHttpException(
                getMessage(TUNNEL_ZONE_ID_IS_INVALID));
        }
        if (dataClient.tunnelZonesGetMembership(tunnelZoneId,
                                                tzHost.hostId) != null) {
            throw new ConflictHttpException(
                getMessage(TUNNEL_ZONE_MEMBER_EXISTS));
        }

        dataClient.tunnelZonesAddMembership(tunnelZoneId, toData(tzHost));
        tunnelZoneEvent.memberCreate(tunnelZoneId,
                dataClient.tunnelZonesGetMembership(
                        tunnelZoneId, tzHost.hostId));
        return Response.created(
            ResourceUriBuilder.getTunnelZoneHost(getBaseUri(), tunnelZoneId,
                                                 tzHost.hostId)).build();
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON })
    public Response create(TunnelZoneHost tunnelZoneHost)
            throws StateAccessException, SerializationException {

        return createTunnelZoneHost(tunnelZoneHost);
    }

    private List<TunnelZoneHost> listTunnelZoneHosts(
            Class<? extends TunnelZoneHost> clazz) throws StateAccessException {
        Set<TunnelZone.HostConfig> dataList =
                dataClient.tunnelZonesGetMemberships(tunnelZoneId);
        List<TunnelZoneHost> tunnelZoneHosts = new ArrayList<>(dataList.size());

        for (TunnelZone.HostConfig data : dataList) {
            TunnelZoneHost tzh = fromData(tunnelZoneId, data);
            if (clazz == null || tzh.getClass().equals(clazz)) {
                tzh.setBaseUri(getBaseUri());
                tunnelZoneHosts.add(tzh);
            }
        }

        return tunnelZoneHosts;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType
            .APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON })
    public List<TunnelZoneHost> listUntypedTunnelZoneHosts() throws
        StateAccessException {

        return listTunnelZoneHosts(null);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType
            .APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON })
    public List<TunnelZoneHost> listGreTunnelZoneHosts() throws
            StateAccessException {

        return listTunnelZoneHosts(TunnelZoneHost.class);
    }

    private TunnelZoneHost getTunnelZoneHost(
            Class<? extends TunnelZoneHost> clazz,
            UUID hostId) throws StateAccessException, SerializationException {
        TunnelZone.HostConfig data =
                dataClient.tunnelZonesGetMembership(tunnelZoneId, hostId);
        if (data == null) {
            throw new NotFoundHttpException("Tunnel zone member was not found");
        }

        TunnelZoneHost tzh = fromData(tunnelZoneId, data);
        if (clazz != null && !tzh.getClass().equals(clazz)) {
            throw new NotFoundHttpException("Tunnel zone was not found");
        }

        tzh.setBaseUri(getBaseUri());
        return tzh;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON })
    @Path("/{hostId}")
    public TunnelZoneHost getUntypedTunnelZoneHost(
            @PathParam("hostId") UUID hostId)
            throws StateAccessException, SerializationException {
        return getTunnelZoneHost(null, hostId);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON })
    @Path("/{hostId}")
    public TunnelZoneHost getGreTunnelZoneHost(@PathParam("hostId")
                                               UUID hostId) throws
            StateAccessException, SerializationException {

        return getTunnelZoneHost(TunnelZoneHost.class, hostId);
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("/{hostId}")
    public void delete(@PathParam("hostId") UUID hostId)
            throws StateAccessException, SerializationException {

        TunnelZone.HostConfig data = dataClient
                .tunnelZonesGetMembership(tunnelZoneId, hostId);
        if (data == null) {
            return;
        }

        dataClient.tunnelZonesDeleteMembership(tunnelZoneId, hostId);
        tunnelZoneEvent.memberDelete(tunnelZoneId, data);
    }
}
