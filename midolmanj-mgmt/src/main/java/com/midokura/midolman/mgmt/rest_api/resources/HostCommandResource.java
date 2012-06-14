/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/23/12
 */
public class HostCommandResource {

    private final static Logger log =
        LoggerFactory.getLogger(HostCommandResource.class);

    private final UUID hostId;

    public HostCommandResource(UUID hostId) {
        this.hostId = hostId;
    }

    @GET
    @PermitAll
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<HostCommand> list(@Context SecurityContext context,
                                  @Context UriInfo uriInfo,
                                  @Context DaoFactory daoFactory,
                                  @Context Authorizer authorizer)
        throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to view hosts.");
        }

        HostDao dao = daoFactory.getHostDao();

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
     * @param context    Object that holds the security data.
     * @param uriInfo    Object that holds the request URI data.
     * @param daoFactory Data access factory object.
     * @param authorizer Authorizer object.
     * @return A Host object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_JSON,
                  MediaType.APPLICATION_JSON})
    public HostCommand get(@PathParam("id") Integer id,
                           @Context SecurityContext context,
                           @Context UriInfo uriInfo,
                           @Context DaoFactory daoFactory,
                           @Context Authorizer authorizer)
        throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException(
                "Not authorized to view this command.");
        }

        HostDao hostDao = daoFactory.getHostDao();

        HostCommand host = hostDao.getCommand(hostId, id);
        host.setBaseUri(uriInfo.getBaseUri());
        return host;
    }

    /**
     * Handler to deleting a host command object.
     *
     * @param id         Command ID from the request.
     * @param context    Object that holds the security data.
     * @param daoFactory Data access factory object.
     * @param authorizer Authorizer object.
     * @return Response object with 204 status code set if successful and 403
     *         is the deletion could not be executed.
     * @throws StateAccessException  Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("{id}")
    public Response delete(@PathParam("id") Integer id,
                           @Context SecurityContext context,
                           @Context DaoFactory daoFactory,
                           @Context Authorizer authorizer)
        throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException(
                "Not authorized to delete this bridge.");
        }

        HostDao hostDao = daoFactory.getHostDao();
        try {
            hostDao.deleteCommand(hostId, id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }

        return Response.noContent().build();
    }
}
