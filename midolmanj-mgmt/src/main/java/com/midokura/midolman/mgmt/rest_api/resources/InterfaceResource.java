/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
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

import com.midokura.midolman.host.commands.DataValidationException;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/30/12
 */
public class InterfaceResource {

    private final UUID hostId;

    private final static Logger log = LoggerFactory
        .getLogger(InterfaceResource.class);

    public InterfaceResource(UUID hostId) {
        this.hostId = hostId;
    }

    /**
     * Handler for creating an interface.
     *
     * @param anInterface Interface object.
     * @param context     Object that holds the security data.
     * @param uriInfo     Object that holds the request URI data.
     * @param daoFactory  Data access factory object.
     * @return Response object with 201 status code set if successful.
     * @throws com.midokura.midolman.state.StateAccessException
     *          Data access error.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Response create(Interface anInterface,
                           @Context SecurityContext context,
                           @Context UriInfo uriInfo,
                           @Context DaoFactory daoFactory)
        throws StateAccessException {

        HostDao hostDao = daoFactory.getHostDao();
        try {

            HostCommand hostCommand =
                hostDao.createCommandForInterfaceUpdate(hostId, null, anInterface);

            if (hostCommand != null) {
                hostCommand.setBaseUri(uriInfo.getBaseUri());

                return Response
                    .ok(hostCommand, VendorMediaType.APPLICATION_HOST_COMMAND_JSON)
                    .location(hostCommand.getUri())
                    .build();
            }

            return Response.status(Response.Status.BAD_REQUEST).build();
        } catch (DataValidationException e) {
            throw new BadRequestHttpException(e.getMessage());
        }
    }

    /**
     * Handler for deleting an interface.
     *
     * @param name        Interface name from the request.
     * @param context     Object that holds the security data.
     * @param daoFactory  Data access factory object.
     * @throws StateAccessException  Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    public void delete(@PathParam("name") String name,
                       @Context SecurityContext context,
                       @Context DaoFactory daoFactory)
        throws StateAccessException {

        HostDao dao = daoFactory.getHostDao();
        try {
            dao.deleteInterface(hostId, name);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler for listing all the interfaces.
     *
     * @param context    Object that holds the security data.
     * @param uriInfo    Object that holds the request URI data.
     * @param daoFactory Data access factory object.
     * @return A list of Interface objects.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Interface> list(@Context SecurityContext context,
                                @Context UriInfo uriInfo,
                                @Context DaoFactory daoFactory)
        throws StateAccessException {

        HostDao dao = daoFactory.getHostDao();

        List<Interface> interfaces = dao.listInterfaces(hostId);

        if (interfaces != null) {
            for (Interface aInterface : interfaces) {
                aInterface.setBaseUri(uriInfo.getBaseUri());
            }
        }

        return interfaces;
    }

    /**
     * Handler to getting an interface.
     *
     * @param name       Interface name from the request.
     * @param context    Object that holds the security data.
     * @param uriInfo    Object that holds the request URI data.
     * @param daoFactory Data access factory object.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    @Produces({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Interface get(@PathParam("name") String name,
                         @Context SecurityContext context,
                         @Context UriInfo uriInfo,
                         @Context DaoFactory daoFactory)
        throws StateAccessException {

        HostDao dao = daoFactory.getHostDao();

        Interface anInterface = dao.getInterface(hostId, name);
        if (anInterface == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        anInterface.setBaseUri(uriInfo.getBaseUri());

        return anInterface;
    }

    /**
     * Handler for updating an interface.
     *
     * @param name       Interface name from the request.
     * @param context    Object that holds the security data.
     * @param uriInfo    Object that holds the request URI data.
     * @param daoFactory Data access factory object.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    public Response update(@PathParam("name") String name,
                           Interface newInterfaceData,
                           @Context SecurityContext context,
                           @Context UriInfo uriInfo,
                           @Context DaoFactory daoFactory)
        throws StateAccessException {

        try {
            HostDao dao = daoFactory.getHostDao();

            HostCommand command =
                dao.createCommandForInterfaceUpdate(hostId, name,
                                                    newInterfaceData);

            command.setBaseUri(uriInfo.getBaseUri());

            return
                Response
                    .ok(command, VendorMediaType.APPLICATION_HOST_COMMAND_JSON)
                    .location(command.getUri())
                    .build();
        } catch (DataValidationException e) {
            throw new BadRequestHttpException(e.getMessage());
        }
    }
}
