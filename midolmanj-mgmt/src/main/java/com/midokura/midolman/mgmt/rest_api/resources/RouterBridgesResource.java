/*
 * @(#)RouteBridgeLinkResource        1.6 3/6/12
 *
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
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterPort;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Sub-resource class for an L2 device connected to a router.
 */
public class RouterBridgesResource {

    private final static Logger log = LoggerFactory
            .getLogger(RouterBridgesResource.class);
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
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
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
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        port.setDeviceId(routerId);
        BridgeRouterLink link = null;
        try {
            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.WRITE, port.getDeviceId(), port.getBridgeId())) {
                throw new UnauthorizedException(
                        "Not authorized to link this router and bridge.");
            }
            link = dao.create(port);
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
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID bridgeId,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        try {
            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.WRITE, routerId, bridgeId)) {
                throw new UnauthorizedException(
                        "Not authorized to unlink this router and bridge.");
            }
            dao.deleteBridgeLink(routerId, bridgeId);
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
     * @throws com.midokura.midolman.mgmt.auth.UnauthorizedException
     *             Authentication/authorization error.
     * @return A BridgeRouterLink object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
            MediaType.APPLICATION_JSON })
    public BridgeRouterLink get(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws StateAccessException, UnauthorizedException {

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        BridgeRouterLink link = null;
        try {
            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.READ, routerId, id)) {
                throw new UnauthorizedException(
                        "Not authorized to view this router to bridge link.");
            }
            link = dao.getBridgeLink(routerId, id);
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

        RouterLinkDao dao = daoFactory.getRouterLinkDao();
        List<BridgeRouterLink> links = null;
        try {
            if (!authorizer.routerAuthorized(
                    context, AuthAction.READ, routerId)) {
                throw new UnauthorizedException(
                        "Not authorized to view this router's bridge links.");
            }
            links = dao.listBridgeLinks(routerId);
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