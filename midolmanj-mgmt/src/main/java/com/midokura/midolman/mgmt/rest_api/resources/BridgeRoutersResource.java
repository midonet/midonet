/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for bridge's linked routers.
 */
public class BridgeRoutersResource {

    private UUID bridgeId = null;

    /**
     * Constructor
     *
     * @param bridgeId
     *            ID of a bridge.
     */
    public BridgeRoutersResource(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * Handler to getting a bridge to router link.
     *
     * @param routerId
     *            Router ID from the request.
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
     * @return A PeerRouterLink object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public BridgeRouterLink get(@PathParam("id") UUID routerId,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException {

        if (!authorizer.routerBridgeLinkAuthorized(context, AuthAction.READ,
                routerId, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge to router link.");
        }

        BridgeLinkDao dao = daoFactory.getBridgeLinkDao();
        BridgeRouterLink link = dao.getRouterLink(bridgeId, routerId);
        if (link != null) {
            link.setBaseUri(uriInfo.getBaseUri());
        }
        return link;
    }

    /**
     * Handler to list all the bridge's links to routers.
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

        if (!authorizer.bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's links.");
        }
        BridgeLinkDao dao = daoFactory.getBridgeLinkDao();
        List<BridgeRouterLink> links = dao.listRouterLinks(bridgeId);
        if (links != null) {
            for (UriResource resource : links) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
        }
        return links;
    }

}