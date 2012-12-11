/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.host.HostInterfacePort;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.NotFoundHttpException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.host.VirtualPortMapping;

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

/**
 * REST API handler for host interface port mapping.
 */
@RequestScoped
public class HostInterfacePortResource {

    private final UUID hostId;
    private final UriInfo uriInfo;
    private final Validator validator;
    private final DataClient dataClient;

    @Inject
    public HostInterfacePortResource(UriInfo uriInfo,
                                     Validator validator,
                                     DataClient dataClient,
                                     @Assisted UUID hostId) {
        this.uriInfo = uriInfo;
        this.validator = validator;
        this.dataClient = dataClient;
        this.hostId = hostId;
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON,
                   MediaType.APPLICATION_JSON })
    public Response create(HostInterfacePort map)
            throws StateAccessException {

        map.setHostId(hostId);

        Set<ConstraintViolation<HostInterfacePort>> violations =
                validator.validate(map, HostInterfacePort
                        .HostInterfacePortCreateGroup.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.hostsAddVrnPortMapping(hostId, map.getPortId(),
                map.getInterfaceName());

        return Response.created(
                ResourceUriBuilder.getHostInterfacePort(uriInfo.getBaseUri(),
                        hostId, map.getPortId()))
                .build();
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{portId}")
    public void delete(@PathParam("portId") UUID portId)
            throws StateAccessException {

        if (!dataClient.hostsVirtualPortMappingExists(hostId, portId)) {
            return;
        }

        dataClient.hostsDelVrnPortMapping(hostId, portId);
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType
            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON})
    public List<HostInterfacePort> list() throws StateAccessException {

        List<VirtualPortMapping> mapConfigs =
                dataClient.hostsGetVirtualPortMappingsByHost(hostId);
        List<HostInterfacePort> maps = new ArrayList<HostInterfacePort>();
        for (VirtualPortMapping mapConfig : mapConfigs) {
            HostInterfacePort map = new HostInterfacePort(
                    hostId, mapConfig);
            map.setBaseUri(uriInfo.getBaseUri());
            maps.add(map);
        }

        return maps;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{portId}")
    public HostInterfacePort get(@PathParam("portId") UUID portId)
        throws StateAccessException {

        VirtualPortMapping data = dataClient.hostsGetVirtualPortMapping(
                hostId, portId);
        if (null == data) {
            throw new NotFoundHttpException("Mapping does not exist");
        }

        HostInterfacePort map = new HostInterfacePort(hostId, data);
        map.setBaseUri(uriInfo.getBaseUri());
        return map;
    }
}
