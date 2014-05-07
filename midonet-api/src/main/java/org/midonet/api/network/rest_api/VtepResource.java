/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.network.VTEP;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.packets.IPv4Addr;
import static org.midonet.api.validation.MessageProperty.VTEP_EXISTS;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class VtepResource extends AbstractVtepResource {

    @Inject
    public VtepResource(RestApiConfig config, UriInfo uriInfo,
                        SecurityContext context, Validator validator,
                        DataClient dataClient, ResourceFactory factory,
                        VtepDataClientProvider vtepClientProvider) {
        super(config, uriInfo, context, validator, dataClient, factory,
              vtepClientProvider);
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_VTEP_JSON,
               VendorMediaType.APPLICATION_JSON})
    public Response create(VTEP vtep)
            throws SerializationException, StateAccessException {

        validate(vtep);

        try {
            org.midonet.cluster.data.VTEP dataVtep = vtep.toData();
            dataClient.vtepCreate(dataVtep);
            return Response.created(ResourceUriBuilder.getVtep(
                    getBaseUri(), dataVtep.getId().toString())).build();
        } catch(StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    VTEP_EXISTS, vtep.getManagementIp()));
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    @Produces({VendorMediaType.APPLICATION_VTEP_JSON,
               MediaType.APPLICATION_JSON})
    public VTEP get(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException, SerializationException {

        IPv4Addr ipAddr = parseIPv4Addr(ipAddrStr);
        org.midonet.cluster.data.VTEP dataVtep = getVtepOrThrow(ipAddr, false);
        PhysicalSwitch ps =
                getPhysicalSwitch(ipAddr, dataVtep.getMgmtPort(), false);

        VTEP vtep = new VTEP(getVtepOrThrow(ipAddr, false), ps);
        vtep.setBaseUri(getBaseUri());
        return vtep;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_VTEP_COLLECTION_JSON,
               MediaType.APPLICATION_JSON})
    public List<VTEP> list()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.VTEP> dataVteps = dataClient.vtepsGetAll();
        List<VTEP> vteps = new ArrayList<>(dataVteps.size());
        for (org.midonet.cluster.data.VTEP dataVtep : dataVteps) {
            PhysicalSwitch ps = getPhysicalSwitch(
                    dataVtep.getId(), dataVtep.getMgmtPort(), false);
            VTEP vtep = new VTEP(dataVtep, ps);
            vtep.setBaseUri(getBaseUri());
            vteps.add(vtep);
        }
        return vteps;
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{ipAddr}")
    public void delete(@PathParam("ipAddr") String ipAddrStr)
            throws StateAccessException {
        // TODO: Verify that it has no bindings to Midonet networks.
    }

    @Path("/{ipAddr}" + ResourceUriBuilder.VTEP_BINDINGS)
    public VtepBindingResource getVtepBindingResource(
        @PathParam("ipAddr") String ipAddrStr) {
        return factory.getVtepBindingResource(ipAddrStr);
    }
}
