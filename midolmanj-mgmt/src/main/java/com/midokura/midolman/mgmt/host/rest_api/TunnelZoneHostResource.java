/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.host.GreTunnelZoneHost;
import com.midokura.midolman.mgmt.host.CapwapTunnelZoneHost;
import com.midokura.midolman.mgmt.host.TunnelZoneHost;
import com.midokura.midolman.mgmt.host.TunnelZoneHostFactory;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.TunnelZone;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST API handler for tunnel zone - host mapping.
 */
@RequestScoped
public class TunnelZoneHostResource {

    private final UUID tunnelZoneId;
    private final UriInfo uriInfo;
    private final Validator validator;
    private final DataClient dataClient;

    @Inject
    public TunnelZoneHostResource(UriInfo uriInfo,
                                  Validator validator,
                                  DataClient dataClient,
                                  @Assisted UUID tunnelZoneId) {
        this.uriInfo = uriInfo;
        this.validator = validator;
        this.dataClient = dataClient;
        this.tunnelZoneId = tunnelZoneId;
    }

    private <T extends TunnelZoneHost> Response createTunnelZoneHost(T tzHost)
            throws StateAccessException {
        tzHost.setTunnelZoneId(tunnelZoneId);
        Set<ConstraintViolation<T>> violations =validator.validate(tzHost);
        if (!violations.isEmpty())
            throw new BadRequestHttpException(violations);

        dataClient.tunnelZonesAddMembership(tunnelZoneId,
                tzHost.toData());

        return Response.created(
                ResourceUriBuilder.getTunnelZoneHost(uriInfo.getBaseUri(),
                        tunnelZoneId, tzHost.getHostId()))
                .build();
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON })
    public Response create(GreTunnelZoneHost tunnelZoneHost)
            throws StateAccessException {

        return createTunnelZoneHost(tunnelZoneHost);
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON })
    public Response create(CapwapTunnelZoneHost tunnelZoneHost)
            throws StateAccessException {

        return createTunnelZoneHost(tunnelZoneHost);
    }

    private List<TunnelZoneHost> listTunnelZoneHosts(
            Class<? extends TunnelZoneHost> clazz) throws StateAccessException {
        Set<TunnelZone.HostConfig<?,
                ?>> dataList =
                dataClient.tunnelZonesGetMemberships(tunnelZoneId);
        List<TunnelZoneHost> tunnelZoneHosts =
                new ArrayList<TunnelZoneHost>();

        for (TunnelZone.HostConfig data : dataList) {
            TunnelZoneHost tzh =
                    TunnelZoneHostFactory.createTunnelZoneHost(
                            tunnelZoneId, data);
            if (clazz == null || tzh.getClass().equals(clazz)) {
                tzh.setBaseUri(uriInfo.getBaseUri());
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

        return listTunnelZoneHosts(GreTunnelZoneHost.class);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType
            .APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON })
    public List<TunnelZoneHost> listCapwapTunnelZoneHosts() throws
            StateAccessException {

        return listTunnelZoneHosts(CapwapTunnelZoneHost.class);
    }

    private TunnelZoneHost getTunnelZoneHost(
            Class<? extends TunnelZoneHost> clazz,
            UUID hostId) throws StateAccessException {
        TunnelZone.HostConfig data = dataClient
                .tunnelZonesGetMembership(tunnelZoneId, hostId);
        if (data == null) {
            throw new NotFoundHttpException("The resource was not found");
        }

        TunnelZoneHost tzh = TunnelZoneHostFactory.createTunnelZoneHost(
                tunnelZoneId, data);
        if (clazz != null && !tzh.getClass().equals(clazz))
            throw new NotFoundHttpException("The resource was not found");

        tzh.setBaseUri(uriInfo.getBaseUri());
        return tzh;
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON })
    @Path("/{hostId}")
    public TunnelZoneHost getUntypedTunnelZoneHost(
            @PathParam("hostId") UUID hostId) throws StateAccessException {
        return getTunnelZoneHost(null, hostId);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON })
    @Path("/{hostId}")
    public TunnelZoneHost getGreTunnelZoneHost(@PathParam("hostId")
                                               UUID hostId) throws
            StateAccessException {

        return getTunnelZoneHost(GreTunnelZoneHost.class, hostId);
    }

    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON })
    @Path("/{hostId}")
    public TunnelZoneHost getCapwapTunnelZoneHost(@PathParam("hostId")
                                               UUID hostId) throws
            StateAccessException {

        return getTunnelZoneHost(CapwapTunnelZoneHost.class, hostId);
    }

    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("/{hostId}")
    public void delete(@PathParam("hostId") UUID hostId)
            throws StateAccessException {

        TunnelZone.HostConfig data = dataClient
                .tunnelZonesGetMembership(tunnelZoneId, hostId);
        if (data == null) {
            return;
        }

        dataClient.tunnelZonesDeleteMembership(tunnelZoneId, hostId);
    }
}
