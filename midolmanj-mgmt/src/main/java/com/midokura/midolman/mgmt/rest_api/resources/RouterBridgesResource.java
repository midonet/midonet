/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterPort;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for an L2 device connected to a router.
 */
public class RouterBridgesResource {

    private UUID routerId = null;

    /**
     * Constructor
     *
     * @param routerId
     *            ID of a router.
     */
    public RouterBridgesResource(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * Handler for creating a router to bridge link.
     *
     * @param port
     *            BridgeRouterPort object.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful. Body is
     *          set to BridgeRouterLink.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(BridgeRouterPort port, @Context UriInfo uriInfo,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerBridgeLinkAuthorized(context, AuthAction.WRITE,
                port.getDeviceId(), port.getBridgeId())) {
            throw new ForbiddenHttpException(
                    "Not authorized to link this router and bridge.");
        }
        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        port.setDeviceId(routerId);
        BridgeRouterLink link = dao.create(port);
        if (link != null) {
            link.setBaseUri(uriInfo.getBaseUri());
        }
        return Response.created(link.getUri()).entity(link).build();
    }

    /**
     * Handler to deleting a router to bridge link.
     *
     * @param bridgeId
     *            Bridge ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID bridgeId,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerBridgeLinkAuthorized(context, AuthAction.WRITE,
                routerId, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to unlink this router and bridge.");
        }
        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        dao.deleteBridgeLink(routerId, bridgeId);
    }

    /**
     * Handler to getting a router to bridge link.
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
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A BridgeRouterLink object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public BridgeRouterLink get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerBridgeLinkAuthorized(context, AuthAction.READ,
                routerId, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router to bridge link.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        BridgeRouterLink link = dao.getBridgeLink(routerId, id);
        if (link != null) {
            link.setBaseUri(uriInfo.getBaseUri());
        }
        return link;
    }

    /**
     * Handler to list router to bridge links.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     * @return A list of BridgeRouterLink objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<BridgeRouterLink> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this router's bridge links.");
        }

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        List<BridgeRouterLink> links = dao.listBridgeLinks(routerId);
        if (links != null) {
            for (UriResource resource : links) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return links;
    }

}