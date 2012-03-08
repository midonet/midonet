/*
 * Copyright 2012 Midokura Europe SARL
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeLinkDao;
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for bridge's linked routers.
 */
public class BridgeRoutersResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeRoutersResource.class);
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
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     * @return A PeerRouterLink object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public BridgeRouterLink get(@PathParam("id") UUID routerId,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException, UnauthorizedException {

        BridgeLinkDao dao = daoFactory.getBridgeLinkDao();
        BridgeRouterLink link = null;
        try {
            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.READ, routerId, bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to view this bridge to router link.");
            }
            link = dao.getRouterLink(bridgeId, routerId);
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
        link.setBaseUri(uriInfo.getBaseUri());
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
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     * @return A list of BridgeRouterLink objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<BridgeRouterLink> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        BridgeLinkDao dao = daoFactory.getBridgeLinkDao();
        List<BridgeRouterLink> links = null;
        try {
            if (!authorizer.bridgeAuthorized(
                    context, AuthAction.READ, bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to view this bridge's links.");
            }
            links = dao.listRouterLinks(bridgeId);
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

        for (UriResource resource : links) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return links;
    }

}