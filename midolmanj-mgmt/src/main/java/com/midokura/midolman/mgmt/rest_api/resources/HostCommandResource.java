/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;
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

import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/23/12
 */
public class HostCommandResource {

    private final static Logger log =
        LoggerFactory.getLogger(HostCommandResource.class);

    private UUID hostId;

    public HostCommandResource(UUID hostId) {
        this.hostId = hostId;
    }

    @GET
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<HostCommand> list(@Context SecurityContext context,
                                  @Context UriInfo uriInfo,
                                  @Context DaoFactory daoFactory,
                                  @Context Authorizer authorizer)
        throws UnauthorizedException, StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new UnauthorizedException("Not authorized to view hosts.");
        }

        HostDao dao = daoFactory.getHostDao();
        try {

            List<HostCommand> hostCommands = dao.listCommands(hostId);

            for (HostCommand hostCommand : hostCommands) {
                hostCommand.setBaseUri(uriInfo.getBaseUri());
            }

            return hostCommands;
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
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
     * @throws UnauthorizedException Authentication/authorization error.
     */
    @GET
    @Path("{id}")
    @Produces({VendorMediaType.APPLICATION_HOST_COMMAND_JSON,
                  MediaType.APPLICATION_JSON})
    public HostCommand get(@PathParam("id") Integer id,
                           @Context SecurityContext context,
                           @Context UriInfo uriInfo,
                           @Context DaoFactory daoFactory,
                           @Context Authorizer authorizer)
        throws StateAccessException, UnauthorizedException {
        try {

            HostDao hostDao = daoFactory.getHostDao();
            if (!authorizer.isAdmin(context)) {
                throw new UnauthorizedException(
                    "Not authorized to view this command.");
            }
            HostCommand host = hostDao.getCommand(hostId, id);
            host.setBaseUri(uriInfo.getBaseUri());
            return host;
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (UnauthorizedException e) {
            log.error("UnauthorizedException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
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
     * @throws UnauthorizedException Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") Integer id,
                           @Context SecurityContext context,
                           @Context DaoFactory daoFactory,
                           @Context Authorizer authorizer)
        throws StateAccessException, UnauthorizedException {

        if (!authorizer.isAdmin(context)) {
            throw new UnauthorizedException(
                "Not authorized to delete this bridge.");
        }

        HostDao hostDao = daoFactory.getHostDao();
        Response response;
        try {

            hostDao.deleteCommand(hostId, id);

            return Response.noContent().build();
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
            response = Response.noContent().build();
        } catch (StateAccessException e) {
            log.error("State Access Exception while deleting an host command");
            throw new UnknownRestApiException(e);
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        return response;
    }
}
