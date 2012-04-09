/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
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

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for Virtual bridges.
 */
public class BridgeResource {
    /*
     * Implements REST API end points for bridges.
     */

    private final static Logger log = LoggerFactory
            .getLogger(BridgeResource.class);

    /**
     * Handler to deleting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this bridge.");
        }

        BridgeDao dao = daoFactory.getBridgeDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        }
    }

    /**
     * Handler to getting a bridge.
     *
     * @param id
     *            Bridge ID from the request.
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
     * @return A Bridge object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Bridge get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge.");
        }

        BridgeDao dao = daoFactory.getBridgeDao();
        Bridge bridge = dao.get(id);
        if (bridge != null) {
            bridge.setBaseUri(uriInfo.getBaseUri());
        }
        return bridge;
    }

    /**
     * Port resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgePortResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.PORTS)
    public BridgePortResource getPortResource(@PathParam("id") UUID id) {
        return new BridgePortResource(id);
    }

    /**
     * Bridge resource locator for router links.
     *
     * @param id
     *            Router ID from the request.
     * @returns RouterBridgesResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTERS)
    public BridgeRoutersResource getRoutersResource(@PathParam("id") UUID id) {
        return new BridgeRoutersResource(id);
    }

    /**
     * Filtering database resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeFilterDbResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.FILTER_DB)
    public BridgeFilterDbResource getBridgeFilterDbResource(
            @PathParam("id") UUID id) {
        return new BridgeFilterDbResource(id);
    }

    /**
     * DHCP resource locator for bridges.
     *
     * @param id
     *            Bridge ID from the request.
     * @returns BridgeDhcpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.DHCP)
    public BridgeDhcpResource getBridgeDhcpResource(@PathParam("id") UUID id) {
        return new BridgeDhcpResource(id);
    }

    /**
     * Handler to updating a bridge.
     *
     * @param id
     *            Bridge ID from the request.
     * @param bridge
     *            Bridge object.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_BRIDGE_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("id") UUID id, Bridge bridge,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge.");
        }
        BridgeDao dao = daoFactory.getBridgeDao();
        bridge.setId(id);
        dao.update(bridge);
        return Response.ok().build();
    }
}
