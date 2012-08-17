/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/30/12
 */
@RequestScoped
public class HostResource {

    private final static Logger log = LoggerFactory
            .getLogger(HostResource.class);

    private final UriInfo uriInfo;
    private final HostDao dao;
    private final ResourceFactory factory;

    @Inject
    public HostResource(UriInfo uriInfo, HostDao dao,
                        ResourceFactory factory) {
        this.uriInfo = uriInfo;
        this.dao = dao;
        this.factory = factory;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Host> list()
        throws ForbiddenHttpException, StateAccessException {

        List<Host> hosts = dao.findAll();
        for (Host host : hosts) {
            host.setBaseUri(uriInfo.getBaseUri());
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

        Host host = dao.get(id);
        if (host != null) {
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
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            throw new ForbiddenHttpException();
        }

        return Response.noContent().build();
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
