/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.rest_api;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.midolman.host.commands.DataValidationException;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.host.HostCommand;
import org.midonet.api.host.Interface;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
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
 *         Date: 1/30/12
 */
@RequestScoped
public class InterfaceResource extends AbstractResource {

    private final static Logger log = LoggerFactory
        .getLogger(InterfaceResource.class);

    private final UUID hostId;

    @Inject
    public InterfaceResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             DataClient dataClient,
                             @Assisted UUID hostId) {
        super(config, uriInfo, context, dataClient);
        this.hostId = hostId;
    }

    /**
     * Handler for creating an interface.
     *
     * @param anInterface Interface object.
     * @return Response object with 201 status code set if successful.
     * @throws org.midonet.midolman.state.StateAccessException
     *          Data access error.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Response create(Interface anInterface)
        throws StateAccessException, SerializationException {

        try {

            Integer id = dataClient.commandsCreateForInterfaceupdate(hostId,
                    null, anInterface.toData());

            if (id != null) {
                HostCommand hostCommand = new HostCommand();
                hostCommand.setId(id);
                hostCommand.setHostId(hostId);
                hostCommand.setBaseUri(getBaseUri());

                return Response
                    .ok(hostCommand,
                            VendorMediaType.APPLICATION_HOST_COMMAND_JSON)
                    .location(hostCommand.getUri())
                    .build();
            }

            return Response.status(Response.Status.BAD_REQUEST).build();
        } catch (DataValidationException e) {
            throw new BadRequestHttpException(e.getMessage());
        }
    }

    /**
     * Handler for listing all the interfaces.
     *
     * @return A list of Interface objects.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Interface> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.host.Interface> ifConfigs =
                dataClient.interfacesGetByHost(hostId);
        List<Interface> interfaces = new ArrayList<Interface>();

        for (org.midonet.cluster.data.host.Interface ifConfig
                : ifConfigs) {
            Interface iface = new Interface(hostId, ifConfig);
            iface.setBaseUri(getBaseUri());
            interfaces.add(iface);
        }

        return interfaces;
    }

    /**
     * Handler to getting an interface.
     *
     * @param name       Interface name from the request.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    @Produces({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Interface get(@PathParam("name") String name)
        throws StateAccessException, SerializationException {

        org.midonet.cluster.data.host.Interface ifaceConfig =
                dataClient.interfacesGet(hostId, name);

        if (ifaceConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        Interface iface = new Interface(hostId, ifaceConfig);
        iface.setBaseUri(getBaseUri());

        return iface;
    }

    /**
     * Handler for updating an interface.
     *
     * @param name       Interface name from the request.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    public Response update(@PathParam("name") String name,
                           Interface newInterfaceData)
        throws StateAccessException, SerializationException {

        try {
            Integer cmdId = dataClient.commandsCreateForInterfaceupdate(
                    hostId, name, newInterfaceData.toData());

            HostCommand command = new HostCommand();
            command.setId(cmdId);
            command.setHostId(hostId);
            command.setBaseUri(getBaseUri());

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
