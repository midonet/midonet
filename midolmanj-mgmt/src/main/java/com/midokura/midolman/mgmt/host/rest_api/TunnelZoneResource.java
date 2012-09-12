/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.host.TunnelZone;
import com.midokura.midolman.mgmt.host.TunnelZoneFactory;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.ResourceFactory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RequestScoped
public class TunnelZoneResource {

    private final static Logger log = LoggerFactory
            .getLogger(TunnelZoneResource.class);

    private final UriInfo uriInfo;
    private final DataClient dataClient;
    private final Validator validator;
    private final ResourceFactory factory;

    @Inject
    public TunnelZoneResource(UriInfo uriInfo, DataClient dataClient,
                              Validator validator, ResourceFactory factory) {
        this.uriInfo = uriInfo;
        this.dataClient = dataClient;
        this.validator = validator;
        this.factory = factory;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<TunnelZone> list() throws StateAccessException {

        List<com.midokura.midonet.cluster.data.TunnelZone<?, ?>>
                tunnelZoneDataList = dataClient.tunnelZonesGetAll();
        List<TunnelZone> tunnelZones = new ArrayList<TunnelZone>();
        for (com.midokura.midonet.cluster.data.TunnelZone<?, ?> zoneData :
                tunnelZoneDataList) {
            TunnelZone zone = TunnelZoneFactory.createTunnelZone(zoneData);
            zone.setBaseUri(uriInfo.getBaseUri());
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
        throws StateAccessException {

        com.midokura.midonet.cluster.data.TunnelZone zoneData =
                dataClient.tunnelZonesGet(id);
        if (zoneData == null) {
            throw new NotFoundHttpException();
        }

        TunnelZone zone = TunnelZoneFactory.createTunnelZone(zoneData);
        zone.setBaseUri(uriInfo.getBaseUri());

        return zone;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException {

        com.midokura.midonet.cluster.data.TunnelZone zoneData =
                dataClient.tunnelZonesGet(id);
        if (zoneData == null) {
            return;
        }

        dataClient.tunnelZonesDelete(id);
    }

    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(TunnelZone tunnelZone)
            throws StateAccessException {

        Set<ConstraintViolation<TunnelZone>> violations = validator.validate(
                tunnelZone, TunnelZone.TunnelZoneCreateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        UUID id = dataClient.tunnelZonesCreate(tunnelZone.toData());
        return Response.created(
                ResourceUriBuilder.getTunnelZone(uriInfo.getBaseUri(), id))
                .build();
    }

    @PUT
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
            MediaType.APPLICATION_JSON })
    @Path("{id}")
    public void update(@PathParam("id") UUID id, TunnelZone tunnelZone)
            throws StateAccessException {

        tunnelZone.setId(id);

        Set<ConstraintViolation<TunnelZone>> violations = validator.validate(
                tunnelZone, TunnelZone.TunnelZoneUpdateGroupSequence.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.tunnelZonesUpdate(tunnelZone.toData());
    }

    @Path("/{id}" + ResourceUriBuilder.HOSTS)
    public TunnelZoneHostResource getTunnelZoneHostResource(
            @PathParam("id") UUID id) {
        return factory.getTunnelZoneHostResource(id);
    }
}
