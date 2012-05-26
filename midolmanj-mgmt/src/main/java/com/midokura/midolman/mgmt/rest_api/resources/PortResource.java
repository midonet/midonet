/*
 * Copyright 2011 Midokura KK
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

import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.AuthRole;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.PortInUseException;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.RouterPort;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.BadRequestHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.mgmt.rest_api.jaxrs.NotFoundHttpException;
import com.midokura.midolman.mgmt.rest_api.resources.BgpResource.PortBgpResource;
import com.midokura.midolman.mgmt.rest_api.resources.VpnResource.PortVpnResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for ports.
 */
public class PortResource {
    /*
     * Implements REST API endpoints for ports.
     */

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    /**
     * Handler to deleting a port.
     * 
     * @param id
     *            Port ID from the request.
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
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete this port.");
        }
        PortDao dao = daoFactory.getPortDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (PortInUseException e) {
            log.error("Attempted to delete a port that is in use", e);
            throw new BadRequestHttpException("Invalid rule position.");
        }
    }

    /**
     * Handler to getting a port.
     * 
     * @param id
     *            Port ID from the request.
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
     * @return A Port object.
     */
    @GET
    @PermitAll
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public Port get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.READ, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this port.");
        }

        PortDao dao = daoFactory.getPortDao();
        Port port = dao.get(id);
        if (port == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        port.setBaseUri(uriInfo.getBaseUri());

        return port;
    }

    /**
     * Handler to updating a port.
     * 
     * @param id
     *            Port ID from the request.
     * @param port
     *            Port object.
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
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, Port port,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this port.");
        }
        PortDao dao = daoFactory.getPortDao();
        port.setId(id);
        dao.update(port);
    }

    /**
     * Handler to linking ports.
     * 
     * @param id
     *            Port ID from the request.
     * @param peerId
     *            Peer port ID.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/link")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void link(@PathParam("id") UUID id, JSONObject input,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        UUID peerId = UUID.fromString(input.get("peerId").toString());
        if (!authorizer.portAuthorized(context, AuthAction.WRITE, id)
                || !authorizer
                        .portAuthorized(context, AuthAction.WRITE, peerId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to link these ports.");
        }

        PortDao dao = daoFactory.getPortDao();
        try {
            dao.link(id, peerId);
        } catch (PortInUseException e) {
            log.error("Attempted to link ports that are in use", e);
            throw new BadRequestHttpException("Invalid rule position.");
        }
    }

    /**
     * Handler to unlinking ports.
     * 
     * @param id
     *            Port ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
    @Path("{id}/unlink")
    @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public void unlink(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.portAuthorized(context, AuthAction.WRITE, id)) {
            throw new ForbiddenHttpException(
                    "Not authorized to unlink this port.");
        }

        PortDao dao = daoFactory.getPortDao();
        dao.unlink(id);
    }

    /**
     * Port resource locator for BGP.
     * 
     * @param id
     *            Port ID from the request.
     * @returns PortBgpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BGP)
    public PortBgpResource getBgpResource(@PathParam("id") UUID id) {
        return new PortBgpResource(id);
    }

    /**
     * Port resource locator for VPN.
     * 
     * @param id
     *            Port ID from the request.
     * @returns PortVpnResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.VPN)
    public PortVpnResource getVpnResource(@PathParam("id") UUID id) {
        return new PortVpnResource(id);
    }

    /**
     * Sub-resource class for bridge's ports.
     */
    public static class BridgePortResource {

        private final UUID bridgeId;

        /**
         * Constructor.
         * 
         * @param bridgeId
         *            UUID of a bridge.
         */
        public BridgePortResource(UUID bridgeId) {
            this.bridgeId = bridgeId;
        }

        /**
         * Handler to create a bridge port.
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
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(BridgePort port, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.bridgeAuthorized(context, AuthAction.WRITE,
                    bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add port to this bridge.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(bridgeId);
            UUID id = dao.create(port);
            return Response.created(
                    ResourceUriBuilder.getPort(uriInfo.getBaseUri(), id))
                    .build();
        }

        /**
         * Handler to list bridge ports.
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            List<Port> ports = dao.listBridgePorts(bridgeId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for bridge's peer ports.
     */
    public static class BridgePeerPortResource {

        private final UUID bridgeId;

        /**
         * Constructor.
         * 
         * @param bridgeId
         *            UUID of a bridge.
         */
        public BridgePeerPortResource(UUID bridgeId) {
            this.bridgeId = bridgeId;
        }

        /**
         * Handler to list bridge peer ports.
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .bridgeAuthorized(context, AuthAction.READ, bridgeId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            List<Port> ports = dao.listBridgePeerPorts(bridgeId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    public static class RouterPortResource {

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
        @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(RouterPort port, @Context UriInfo uriInfo,
                @Context SecurityContext context,
                @Context DaoFactory daoFactory, @Context Authorizer authorizer)
                throws StateAccessException {

            if (!authorizer.routerAuthorized(context, AuthAction.WRITE,
                    routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to add port to this router.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(routerId);
            UUID id = dao.create(port);
            return Response.created(
                    ResourceUriBuilder.getPort(uriInfo.getBaseUri(), id))
                    .build();
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .routerAuthorized(context, AuthAction.READ, routerId)) {
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

    /**
     * Sub-resource class for router peer ports.
     */
    public static class RouterPeerPortResource {

        private final UUID routerId;

        /**
         * Constructor.
         * 
         * @param routerId
         *            UUID of a router.
         */
        public RouterPeerPortResource(UUID routerId) {
            this.routerId = routerId;
        }

        /**
         * Handler to list router peer ports.
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
        @PermitAll
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
                @Context Authorizer authorizer) throws StateAccessException {

            if (!authorizer
                    .routerAuthorized(context, AuthAction.READ, routerId)) {
                throw new ForbiddenHttpException(
                        "Not authorized to view these ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            List<Port> ports = dao.listRouterPeerPorts(routerId);
            if (ports != null) {
                for (UriResource resource : ports) {
                    resource.setBaseUri(uriInfo.getBaseUri());
                }
            }
            return ports;
        }
    }
}
