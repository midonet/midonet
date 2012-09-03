/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.host.HostCommand;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.cluster.data.host.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
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
    private final DataClient dataClient;

    @Inject
    public HostCommandResource(UriInfo uriInfo,
                               SecurityContext context,
                               DataClient dataClient,
                               @Assisted UUID hostId) {
        this.context = context;
        this.uriInfo = uriInfo;
        this.dataClient = dataClient;
        this.hostId = hostId;
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<HostCommand> list()
        throws StateAccessException {

        List<Command> commandConfigs = dataClient.commandsGetByHost(hostId);
        List<HostCommand> commands = new ArrayList<HostCommand>();
        for (Command commandConfig : commandConfigs) {
            HostCommand command = new HostCommand(hostId, commandConfig);
            command.setBaseUri(uriInfo.getBaseUri());
            commands.add(command);
        }

        return commands;
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

        Command commandConfig = dataClient.commandsGet(hostId, id);
        HostCommand command = null;
        if (commandConfig != null) {
            command = new HostCommand(hostId, commandConfig);
            command.setBaseUri(uriInfo.getBaseUri());
        }
        return command;
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

        dataClient.commandsDelete(hostId, id);
        return Response.noContent().build();
    }
}
