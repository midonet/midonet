/*
 * @(#)PortResource        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
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

import com.midokura.midolman.mgmt.auth.AuthManager;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.mgmt.rest_api.resources.BgpResource.PortBgpResource;
import com.midokura.midolman.mgmt.rest_api.resources.VpnResource.PortVpnResource;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for ports.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class PortResource {
    /*
     * Implements REST API endpoints for ports.
     */

    private final static Logger log = LoggerFactory
            .getLogger(PortResource.class);

    /**
     * Port resource locator for BGP.
     *
     * @param id
     *            Port ID from the request.
     * @returns PortBgpResource object to handle sub-resource requests.
     */
    @Path("/{id}" + UriManager.BGP)
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
    @Path("/{id}" + UriManager.VPN)
    public PortVpnResource getVpnResource(@PathParam("id") UUID id) {
        return new PortVpnResource(id);
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
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A Port object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_PORT_JSON,
            MediaType.APPLICATION_JSON })
    public Port get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        PortDao dao = daoFactory.getPortDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only see your own port.");
        }

        Port port = null;
        try {
            port = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        port.setBaseUri(uriInfo.getBaseUri());
        return port;
    }

    /**
     * Handler to deleting a port.
     *
     * @param id
     *            Port ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory)
            throws StateAccessException, UnauthorizedException {
        PortDao dao = daoFactory.getPortDao();
        if (!AuthManager.isOwner(context, (OwnerQueryable) dao, id)) {
            throw new UnauthorizedException("Can only delete your own port.");
        }

        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Sub-resource class for bridge's ports.
     */
    public static class BridgePortResource {

        private UUID bridgeId = null;

        /**
         * Constructor.
         *
         * @param bridgeId
         *            UUID of a bridge.
         */
        public BridgePortResource(UUID bridgeId) {
            this.bridgeId = bridgeId;
        }

        private boolean isBridgeOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            BridgeDao q = daoFactory.getBridgeDao();
            return AuthManager.isOwner(context, (OwnerQueryable) q, bridgeId);
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(BridgePort port, @Context UriInfo uriInfo,
                @Context SecurityContext context, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!isBridgeOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(bridgeId);
            port.setVifId(null); // Don't allow any VIF plugging in create.

            UUID id = null;
            try {
                id = dao.create(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response.created(
                    UriManager.getPort(uriInfo.getBaseUri(), id)).build();
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A list of Port objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {

            if (!isBridgeOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            List<Port> ports = null;
            try {
                ports = dao.listBridgePorts(bridgeId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            for (UriResource resource : ports) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return ports;
        }
    }

    /**
     * Sub-resource class for router's ports.
     */
    public static class RouterPortResource {

        private UUID routerId = null;

        /**
         * Constructor.
         *
         * @param routerId
         *            UUID of a router.
         */
        public RouterPortResource(UUID routerId) {
            this.routerId = routerId;
        }

        private boolean isRouterOwner(SecurityContext context,
                DaoFactory daoFactory) throws StateAccessException {
            RouterDao q = daoFactory.getRouterDao();
            return AuthManager.isOwner(context, (OwnerQueryable) q, routerId);
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @returns Response object with 201 status code set if successful.
         */
        @POST
        @Consumes({ VendorMediaType.APPLICATION_PORT_JSON,
                MediaType.APPLICATION_JSON })
        public Response create(MaterializedRouterPort port,
                @Context UriInfo uriInfo, @Context SecurityContext context,
                @Context DaoFactory daoFactory) throws StateAccessException,
                UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            port.setDeviceId(routerId);
            port.setVifId(null); // Don't allow any VIF plugging in create.

            UUID id = null;
            try {
                id = dao.create(port);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }

            return Response.created(
                    UriManager.getPort(uriInfo.getBaseUri(), id)).build();
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
         * @throws StateAccessException
         *             Data access error.
         * @throws UnauthorizedException
         *             Authentication/authorization error.
         * @return A list of Port objects.
         */
        @GET
        @Produces({ VendorMediaType.APPLICATION_PORT_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<Port> list(@Context SecurityContext context,
                @Context UriInfo uriInfo, @Context DaoFactory daoFactory)
                throws StateAccessException, UnauthorizedException {
            if (!isRouterOwner(context, daoFactory)) {
                throw new UnauthorizedException("Can only see your own ports.");
            }

            PortDao dao = daoFactory.getPortDao();
            List<Port> ports = null;
            try {
                ports = dao.listRouterPorts(routerId);
            } catch (StateAccessException e) {
                log.error("Error accessing data", e);
                throw e;
            } catch (Exception e) {
                log.error("Unhandled error", e);
                throw new UnknownRestApiException(e);
            }
            for (UriResource resource : ports) {
                resource.setBaseUri(uriInfo.getBaseUri());
            }
            return ports;
        }
    }
}
