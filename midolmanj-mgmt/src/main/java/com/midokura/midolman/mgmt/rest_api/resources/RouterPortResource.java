/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for router's ports.
 */
public class RouterPortResource {

    private final UUID routerId;

    /**
     * Constructor.
     *
     * @param routerId
     *            UUID of a router.
     */
    public RouterPortResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler to create a router port.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(MaterializedRouterPort port,
            @Context UriInfo uriInfo, @Context SecurityContext context,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.WRITE, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to add port to this router.");
        }

        PortDao dao = daoFactory.getPortDao();
        port.setDeviceId(routerId);
        port.setVifId(null); // Don't allow any VIF plugging in create.
        UUID id = dao.create(port);
        return Response.created(
                ResourceUriBuilder.getPort(uriInfo.getBaseUri(), id)).build();
    }

    /**
     * Handler to list router ports.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @return A list of Port objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Port> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view these ports.");
        }

        PortDao dao = daoFactory.getPortDao();
        List<Port> ports = dao.listRouterPorts(routerId);
        if (ports != null) {
            for (UriResource resource : ports) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return ports;
    }
}