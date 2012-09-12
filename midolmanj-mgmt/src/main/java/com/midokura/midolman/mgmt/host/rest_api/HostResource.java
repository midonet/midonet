/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.ForbiddenHttpException;
import com.midokura.midolman.mgmt.host.Host;
import com.midokura.midolman.mgmt.host.HostInterfacePortMap;
import com.midokura.midolman.mgmt.host.HostInterfacePortMap.HostInterfacePortMapCreateGroup;
import com.midokura.midolman.mgmt.rest_api.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.ResourceFactory;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.host.VirtualPortMapping;
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

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/30/12
 */
@RequestScoped
public class HostResource {

    private final static Logger log = LoggerFactory
            .getLogger(HostResource.class);

    private final UriInfo uriInfo;
    private final DataClient dataClient;
    private final Validator validator;
    private final ResourceFactory factory;

    @Inject
    public HostResource(UriInfo uriInfo, DataClient dataClient,
                        Validator validator, ResourceFactory factory) {
        this.uriInfo = uriInfo;
        this.dataClient = dataClient;
        this.validator = validator;
        this.factory = factory;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Host> list()
        throws ForbiddenHttpException, StateAccessException {

        List<com.midokura.midonet.cluster.data.host.Host> hostConfigs =
                dataClient.hostsGetAll();
        List<Host> hosts = new ArrayList<Host>();
        for (com.midokura.midonet.cluster.data.host.Host hostConfig :
                hostConfigs) {
            Host host = new Host(hostConfig);
            host.setBaseUri(uriInfo.getBaseUri());
            hosts.add(host);
        }
        return hosts.size() > 0 ? hosts : null;
    }

    /**
     * Handler to getting a host information.
     *
     * @param id         Host ID from the request.
     * @return A Host object.
     * @throws StateAccessException Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_HOST_JSON,
                  MediaType.APPLICATION_JSON})
    public Host get(@PathParam("id") UUID id)
        throws StateAccessException {

        com.midokura.midonet.cluster.data.host.Host hostConfig =
                dataClient.hostsGet(id);
        Host host = null;
        if (hostConfig != null) {
            host = new Host(hostConfig);
            host.setBaseUri(uriInfo.getBaseUri());
        }

        return host;
    }

    /**
     * Handler to deleting a host.
     *
     * @param id         Host ID from the request.
     * @return Response object with 204 status code set if successful and 403 is
     *         the deletion could not be executed.
     * @throws StateAccessException Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public Response delete(@PathParam("id") UUID id)
            throws StateAccessException, InvalidStateOperationException {

        try {
            dataClient.hostsDelete(id);
        } catch (StateAccessException e) {
            throw new ForbiddenHttpException();
        }
        return Response.noContent().build();

    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_HOST_JSON,
            MediaType.APPLICATION_JSON })
    @Path("{id}" + ResourceUriBuilder.INTERFACE_PORT_MAP)
    public Response createHostInterfacePortMap(@PathParam("id") UUID id,
                                               HostInterfacePortMap map)
            throws StateAccessException {

        map.setHostId(id);

        Set<ConstraintViolation<HostInterfacePortMap>> violations =
                validator.validate(map, HostInterfacePortMapCreateGroup.class);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.hostsAddVrnPortMapping(id, map.getPortId(),
                map.getInterfaceName());

    	return Response.noContent().build();
    }

    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}" + ResourceUriBuilder.INTERFACE_PORT_MAP)
    public void deleteHostInterfacePortMap(@PathParam("id") UUID id,
                                           HostInterfacePortMap map)
            throws StateAccessException {

        map.setHostId(id);

        Set<ConstraintViolation<HostInterfacePortMap>> violations =
                validator.validate(map);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.hostsDelVrnPortMapping(id, map.getPortId());
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}" + ResourceUriBuilder.INTERFACE_PORT_MAP)
    @Produces({VendorMediaType.APPLICATION_HOST_JSON,
            MediaType.APPLICATION_JSON})
    public List<HostInterfacePortMap> getHostInterfacePortMaps(
            @PathParam("id") UUID id) throws StateAccessException {

        List<VirtualPortMapping> mapConfigs =
                dataClient.hostsGetVirtualPortMappingsByHost(id);
        List<HostInterfacePortMap> maps = new ArrayList<HostInterfacePortMap>();
        for (VirtualPortMapping mapConfig : mapConfigs) {
            HostInterfacePortMap map = new HostInterfacePortMap(id, mapConfig);
            map.setBaseUri(uriInfo.getBaseUri());
            maps.add(map);
        }

        return maps;
    }

    /**
     * Interface resource locator for hosts.
     *
     * @param hostId Host ID from the request.
     * @return InterfaceResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.INTERFACES)
    public InterfaceResource getInterfaceResource(
            @PathParam("id") UUID hostId) {

        return factory.getInterfaceResource(hostId);
    }

    /**
     * Interface resource locator for hosts.
     *
     * @param hostId Host ID from the request.
     * @return InterfaceResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.COMMANDS)
    public HostCommandResource getHostCommandsResource(
        @PathParam("id") UUID hostId) {
        return factory.getHostCommandsResource(hostId);
    }
}
