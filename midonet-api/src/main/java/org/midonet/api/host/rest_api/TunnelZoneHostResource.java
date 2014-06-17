/*
 * Copyright 2012 Midokura PTE LTD.
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
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.host.TunnelZoneHost;
import org.midonet.api.host.TunnelZoneHostFactory;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.event.topology.TunnelZoneEvent;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

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
        tzHost.setTunnelZoneId(tunnelZoneId);

        validate(tzHost, TunnelZoneHost.TunnelZoneHostCreateGroupSequence.class);

        dataClient.tunnelZonesAddMembership(tunnelZoneId,
                tzHost.toData());
        tunnelZoneEvent.memberCreate(tunnelZoneId,
                dataClient.tunnelZonesGetMembership(
                        tunnelZoneId, tzHost.getHostId()));
        return Response.created(
                ResourceUriBuilder.getTunnelZoneHost(getBaseUri(),
                        tunnelZoneId, tzHost.getHostId()))
                .build();
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON })
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
            TunnelZoneHost tzh = TunnelZoneHostFactory.createTunnelZoneHost(
                            tunnelZoneId, data);
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
            throw new NotFoundHttpException("The resource was not found");
        }

        TunnelZoneHost tzh = TunnelZoneHostFactory.createTunnelZoneHost(
                tunnelZoneId, data);
        if (clazz != null && !tzh.getClass().equals(clazz))
            throw new NotFoundHttpException("The resource was not found");

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
