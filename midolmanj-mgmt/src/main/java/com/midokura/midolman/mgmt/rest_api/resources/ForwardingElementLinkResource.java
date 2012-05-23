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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeLinkDao;
import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterPort;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Resource class for forward element links
 */
public class ForwardingElementLinkResource {

    /**
     * Sub-resource class for bridge's linked routers.
     */
    public static class BridgeRoutersResource {

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
        @PermitAll
        @Path("{id}")
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public BridgeRouterLink get(@PathParam("id") UUID routerId,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.READ, routerId, bridgeId)) {
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<BridgeRouterLink> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
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

    /**
     * Sub-resource class for an L2 device connected to a router.
     */
    public static class RouterBridgesResource {

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
         * @returns Response object with 201 status code set if successful. Body
         *          is set to BridgeRouterLink.
         */
        @POST
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(BridgeRouterPort port, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.WRITE, port.getDeviceId(), port.getBridgeId())) {
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
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Path("{id}")
        public void delete(@PathParam("id") UUID bridgeId,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.WRITE, routerId, bridgeId)) {
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
        @PermitAll
        @Path("{id}")
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public BridgeRouterLink get(@PathParam("id") UUID id,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerBridgeLinkAuthorized(context,
                    AuthAction.READ, routerId, id)) {
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<BridgeRouterLink> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .routerAuthorized(context, AuthAction.READ, routerId)) {
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

    /**
     * Sub-resource class for router's peer router.
     */
    public static class RouterRoutersResource {

        private UUID routerId = null;

        /**
         * Constructor
         *
         * @param routerId
         *            ID of a router.
         */
        public RouterRoutersResource(UUID routerId) {
            this.routerId = routerId;
        }

        /**
         * Handler for creating a router to router link.
         *
         * @param port
         *            LogicalRouterPort object.
         * @param uriInfo
         *            Object that holds the request URI data.
         * @param context
         *            Object that holds the security data.
         * @param daoFactory
         *            Data access factory object.
         * @param authorizer
         *            Authorizer object.
         * @throws StateAccessException
         *             Data access error.
         * @returns Response object with 201 status code set if successful. Body is
         *          set to PeerRouterLink.
         */
        @POST
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(LogicalRouterPort port, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer.routerLinkAuthorized(context, AuthAction.WRITE,
                    port.getDeviceId(), port.getPeerRouterId())) {
                throw new ForbiddenHttpException(
                        "Not authorized to link these routers.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            port.setDeviceId(routerId);
            PeerRouterLink peerRouter = dao.create(port);
            if (peerRouter != null) {
                peerRouter.setBaseUri(uriInfo.getBaseUri());
            }
            return Response.created(peerRouter.getUri()).entity(peerRouter).build();
        }

        /**
         * Handler to deleting a router link.
         *
         * @param peerId
         *            Peer router ID from the request.
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
        @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
        @Path("{id}")
        public void delete(@PathParam("id") UUID peerId,
                @Context SecurityContext context, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer.routerLinkAuthorized(context, AuthAction.WRITE,
                    routerId, peerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to unlink these routers.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            dao.delete(routerId, peerId);
        }

        /**
         * Handler to getting a router to router link.
         *
         * @param id
         *            Peer router ID from the request.
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
         * @return A PeerRouterLink object.
         */
        @GET
        @PermitAll
        @Path("{id}")
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_JSON,
                MediaType.APPLICATION_JSON })
        public PeerRouterLink get(@PathParam("id") UUID id,
                @Context SecurityContext context, @Context UriInfo uriInfo,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerLinkAuthorized(context, AuthAction.READ,
                    routerId, id)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view this router link.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            PeerRouterLink link = dao.get(routerId, id);
            if (link == null) {
                throw new NotFoundHttpException(
                        "The requested resource was not found.");
            }
            link.setBaseUri(uriInfo.getBaseUri());

            return link;
        }

        /**
         * Handler to list router links.
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
         * @return A list of PeerRouterLink objects.
         */
        @GET
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_ROUTER_LINK_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<PeerRouterLink> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer.routerAuthorized(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these router links.");
            }

            RouterLinkDao dao = daoFactory.getRouterLinkDao();
            List<PeerRouterLink> links = dao.list(routerId);
            if (links != null) {
                for (UriResource resource : links) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return links;
        }
    }
}
