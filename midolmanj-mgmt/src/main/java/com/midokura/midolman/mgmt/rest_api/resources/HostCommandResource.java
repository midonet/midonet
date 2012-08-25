/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.http.VendorMediaType;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/23/12
 */
@RequestScoped
public class HostCommandResource {

    private final static Logger log =
        LoggerFactory.getLogger(HostCommandResource.class);

    private final UUID hostId;
    private final SecurityContext context;
    private final UriInfo uriInfo;
    private final HostDao dao;

    @Inject
    public HostCommandResource(UriInfo uriInfo,
                               SecurityContext context,
                               HostDao dao,
                               @Assisted UUID hostId) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.dao = dao;
        this.hostId = hostId;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<HostCommand> list()
        throws StateAccessException {

        List<HostCommand> hostCommands = dao.listCommands(hostId);
        if (hostCommands != null) {
            for (HostCommand hostCommand : hostCommands) {
                hostCommand.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return hostCommands;
    }

    /**
     * Handler for getting a host command information.
     *
     * @param id         Command ID from the request.
     * @return A Host object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_JSON,
                  MediaType.APPLICATION_JSON})
    public HostCommand get(@PathParam("id") Integer id)
        throws StateAccessException {

        HostCommand host = dao.getCommand(hostId, id);
        host.setBaseUri(uriInfo.getBaseUri());
        return host;
    }

    /**
     * Handler to deleting a host command object.
     *
     * @param id         Command ID from the request.
     * @return Response object with 204 status code set if successful and 403
     *         is the deletion could not be executed.
     * @throws StateAccessException  Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{id}")
    public Response delete(@PathParam("id") Integer id)
        throws StateAccessException {

        try {
            dao.deleteCommand(hostId, id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }

        return Response.noContent().build();
    }
}
